package com.winrun.bot;

import com.winrun.Config;
import com.winrun.integrations.BitrixClient;
import com.winrun.integrations.RobokassaService;
import com.winrun.model.*;
import com.winrun.repo.OrderRepo;
import com.winrun.repo.ProductRepo;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class WinrunBot extends TelegramLongPollingBot {
    private final Config cfg;
    private final ProductRepo products;
    private final OrderRepo orders;
    private final BitrixClient bitrix;
    private final RobokassaService rk;

    private final Map<Long, ConversationState> states = new ConcurrentHashMap<>();
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    // Сообщения альбома и карточки — чтобы удалять при смене цвета
    private final Map<Long, List<Integer>> lastAlbumMessageIds = new ConcurrentHashMap<>();
    private final Map<Long, Integer> lastCardMessageId = new ConcurrentHashMap<>();

    // Кэш file_id: ключ = canonicalRef (путь/URL), значение = file_id
    private final Map<String, String> fileIdCache = new ConcurrentHashMap<>();

    private static final Pattern PHONE_RU = Pattern.compile("^(\\+7|8)\\d{10}$");

    public WinrunBot(Config cfg, ProductRepo products, OrderRepo orders, BitrixClient bitrix, RobokassaService rk) {
        super(cfg.botToken());
        this.cfg = cfg;
        this.products = products;
        this.orders = orders;
        this.bitrix = bitrix;
        this.rk = rk;

        try { this.products.seedIfEmpty(); } catch (Exception e) {
            System.out.println("Seed catalog failed: " + e.getMessage());
        }
    }

    @Override public String getBotUsername() { return cfg.botUsername(); }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) onMessage(update.getMessage());
            else if (update.hasCallbackQuery()) onCallback(update.getCallbackQuery());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void onMessage(Message m) throws Exception {
        long chatId = m.getChatId();
        states.putIfAbsent(chatId, ConversationState.IDLE);
        if (!m.hasText()) return;

        String text = m.getText().trim();
        if ("/start".equals(text)) { sendMainMenu(chatId); return; }

        switch (text) {
            case "👟 Каталог" -> showModelsList(chatId);
            case "📦 Мои заказы" -> showMyOrders(chatId);
            case "ℹ️ О коллекции" -> sendText(chatId, """
                    Первая коллекция Winrun.
                    Лимитированный дроп — всего 300 пар.
                    Современные повседневные кроссовки по цене 8990 ₽ (доставка включена).
                    Доставка по России.
                    """);
            case "💬 Поддержка" -> sendText(chatId, "Напишите оператору: " + cfg.supportUsername());
            default -> proceedFlow(chatId, text);
        }
    }

    /* ===================== Каталог: список моделей ===================== */

    private void showModelsList(long chatId) throws Exception {
        int used = orders.countNonFailed();
        if (used >= cfg.dropLimit()) {
            sendText(chatId, "❌ Предзаказ закрыт: лимит дропа (" + cfg.dropLimit() + " пар) достигнут.");
            return;
        }
        List<Product> list = products.listActive();
        if (list.isEmpty()) {
            sendText(chatId, "Каталог пуст. Обратитесь к администратору.");
            return;
        }

        // Кнопки моделей (2 в ряд)
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (Product p : list) {
            InlineKeyboardButton b = new InlineKeyboardButton(p.name);
            b.setCallbackData("model:" + p.id);
            row.add(b);
            if (row.size() == 2) { rows.add(new ArrayList<>(row)); row.clear(); }
        }
        if (!row.isEmpty()) rows.add(row);

        SendMessage sm = new SendMessage(String.valueOf(chatId), "Выберите модель:");
        sm.setReplyMarkup(inline(rows));
        execute(sm);

        states.put(chatId, ConversationState.SELECT_PRODUCT);
    }

    /* ===================== Помощники по фото ===================== */

    private String imagesBasePathSafe() {
        try {
            var m = Config.class.getMethod("imagesBasePath");
            Object v = m.invoke(cfg);
            if (v != null) return v.toString();
        } catch (Exception ignore) {}
        return "."; // текущая директория запуска
    }

    private boolean isHttpUrl(String ref) {
        return ref != null && (ref.startsWith("http://") || ref.startsWith("https://"));
    }

    private File resolveLocalFile(String ref) {
        File f = new File(ref);
        if (!f.isAbsolute()) f = new File(imagesBasePathSafe(), ref);
        return f;
    }

    /** Новый порядок: base.jpg, base_4.jpg, base_3.jpg, base_5.jpg, base_2.jpg */
    private List<String> buildStrictRefs(String mainFilename) {
        List<String> out = new ArrayList<>();
        if (mainFilename == null || mainFilename.isBlank()) return out;

        String name = mainFilename.trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot) : "";

        // порядок для всех моделей/вариантов: 1, 4, 3, 5, 2
        out.add(base + ext);          // 1: base.jpg (например flow1.jpg)
        out.add(base + "_4" + ext);   // 2
        out.add(base + "_3" + ext);   // 3
        out.add(base + "_5" + ext);   // 4
        out.add(base + "_2" + ext);   // 5
        return out;
    }

    private String buildCaption(Product p, Product.Variant v) {
        return "*" + p.name + "*\n" +
                (p.description == null || p.description.isBlank() ? "" : p.description + "\n") +
                "Цвет: " + (v.color==null? "-" : v.color) + "\n" +
                "Цена: " + (p.price > 0 ? p.price : cfg.priceRub()) + " ₽";
    }

    /** Загрузить фото (если локальное) и закэшировать file_id. Вернёт список file_id по refs (только найденные). */
    private List<String> ensureFileIds(long chatId, List<String> refs) throws TelegramApiException {
        List<String> out = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) continue;

            String key = canonicalRef(ref);
            String cached = fileIdCache.get(key);
            if (cached != null && !cached.isBlank()) {
                out.add(cached);
                continue;
            }

            // Если это URL, можно сразу использовать его как media, НО для единообразия и надёжности тоже прогрузим в file_id.
            InputFile toSend;
            if (isHttpUrl(ref)) {
                toSend = new InputFile(ref);
            } else {
                File f = resolveLocalFile(ref);
                if (!f.exists() || !f.isFile()) {
                    System.out.println("[IMG] Not found: " + f.getAbsolutePath());
                    continue;
                }
                toSend = new InputFile(f, f.getName());
            }

            // Временная отправка, чтобы получить file_id
            SendPhoto sp = new SendPhoto(String.valueOf(chatId), toSend);
            Message msg = execute(sp);
            String fid = extractLargestPhotoFileId(msg);
            if (fid != null) {
                fileIdCache.put(key, fid);
                out.add(fid);
            }

            // Удалим временный месседж
            try { execute(new DeleteMessage(String.valueOf(chatId), msg.getMessageId())); } catch (Exception ignore) {}
        }
        return out;
    }

    private String canonicalRef(String ref) {
        if (ref == null) return "";
        String r = ref.trim();
        if (isHttpUrl(r)) return r;
        // для локальных — абсолютный путь как ключ
        File f = resolveLocalFile(r);
        return f.getAbsolutePath();
    }

    private String extractLargestPhotoFileId(Message msg) {
        if (msg == null || msg.getPhoto() == null || msg.getPhoto().isEmpty()) return null;
        PhotoSize best = null;
        for (PhotoSize ps : msg.getPhoto()) {
            if (best == null) best = ps;
            else {
                Integer bs = best.getFileSize(), cs = ps.getFileSize();
                if (bs == null || (cs != null && cs > bs)) best = ps;
            }
        }
        return best != null ? best.getFileId() : null;
    }

    private InlineKeyboardMarkup controlKb() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // навигация по цветам
        rows.add(List.of(
                button("◀", "vprev"),
                button("🔁 Цвета", "noop"),
                button("▶", "vnext")
        ));

        // действие: выбрать текущую
        rows.add(List.of(
                button("Выбрать ✅", "pickModel")
        ));

        // новые кнопки
        rows.add(List.of(
                button("🔁 Модели", "chooseModel"),
                button("🏠 Меню", "goMenu")
        ));

        return inline(rows);
    }

    private InlineKeyboardButton button(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(data);
        return b;
    }

    /* ===================== Альбом + карточка ===================== */

    private void sendVariantAlbumThenCard(long chatId) throws Exception {
        Session s = sessions.computeIfAbsent(chatId, k -> new Session());
        if (s.selectedModelId == null) { showModelsList(chatId); return; }
        var opt = products.find(s.selectedModelId);
        if (opt.isEmpty()) { showModelsList(chatId); return; }
        Product p = opt.get();
        if (p.variants == null || p.variants.isEmpty()) {
            sendText(chatId, "Для этой модели пока нет вариантов цветов.");
            return;
        }

        int idx = (s.variantIndex == null) ? 0 : s.variantIndex;
        if (idx < 0 || idx >= p.variants.size()) idx = 0;
        Product.Variant v = p.variants.get(idx);

        // Имена файлов по схеме base.jpg, base_2.jpg ... base_5.jpg
        List<String> refs = buildStrictRefs(v.image);

        // Гарантируем file_id для каждого доступного изображения
        List<String> fids = ensureFileIds(chatId, refs);

        // Формируем альбом из file_id (строки). Telegram требует >= 2 для SendMediaGroup
        List<InputMedia> media = new ArrayList<>();
        for (String fid : fids) {
            InputMediaPhoto photo = new InputMediaPhoto();
            photo.setMedia(fid); // <-- ВАЖНО: строка (file_id)
            media.add(photo);
            if (media.size() == 5) break;
        }

        List<Integer> mids = new ArrayList<>();

        if (media.size() >= 2) {
            SendMediaGroup group = new SendMediaGroup();
            group.setChatId(String.valueOf(chatId));
            group.setMedias(media);
            List<Message> responses = execute(group);
            for (Message mm : responses) mids.add(mm.getMessageId());
        } else if (media.size() == 1) {
            // fallback: одно фото отдельно
            SendPhoto sp = new SendPhoto(String.valueOf(chatId), new InputFile(fids.get(0)));
            Message m = execute(sp);
            mids.add(m.getMessageId());
        } else {
            // нет валидных изображений
        }

        lastAlbumMessageIds.put(chatId, mids);

        // Затем карточка (название/описание/цена + кнопки)
        String caption = buildCaption(p, v);
        SendMessage card = new SendMessage(String.valueOf(chatId), caption);
        card.setParseMode(ParseMode.MARKDOWN);
        card.setReplyMarkup(controlKb());
        Message cardMsg = execute(card);
        lastCardMessageId.put(chatId, cardMsg.getMessageId());
    }

    /* ===================== Callbacks ===================== */

    private void onCallback(CallbackQuery q) throws Exception {
        long chatId = q.getMessage().getChatId();
        String data = q.getData();

        if ("noop".equals(data)) return;

        if ("chooseModel".equals(data)) {
            showModelsList(chatId);
            return;
        }

        if ("goMenu".equals(data)) {
            sendMainMenu(chatId);
            return;
        }

        if (data.startsWith("model:")) {
            int id = Integer.parseInt(data.substring(6));
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.selectedModelId = id;
            s.variantIndex = 0;

            sendVariantAlbumThenCard(chatId);
            return;
        }

        if ("vnext".equals(data) || "vprev".equals(data)) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            if (s.selectedModelId == null) { showModelsList(chatId); return; }
            var opt = products.find(s.selectedModelId);
            if (opt.isEmpty()) { showModelsList(chatId); return; }
            Product p = opt.get();
            int n = (p.variants == null) ? 0 : p.variants.size();
            if (n == 0) { showModelsList(chatId); return; }

            int idx = (s.variantIndex == null) ? 0 : s.variantIndex;
            if ("vnext".equals(data)) idx = (idx + 1) % n; else idx = (idx - 1 + n) % n;
            s.variantIndex = idx;

            sendVariantAlbumThenCard(chatId);
            return;
        }

        if ("pickModel".equals(data)) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            if (s.selectedModelId == null) { showModelsList(chatId); return; }
            var opt = products.find(s.selectedModelId);
            if (opt.isEmpty()) { showModelsList(chatId); return; }
            Product p = opt.get();

            int idx = (s.variantIndex == null) ? 0 : s.variantIndex;
            if (p.variants == null || p.variants.isEmpty()) { showModelsList(chatId); return; }
            if (idx < 0 || idx >= p.variants.size()) idx = 0;

            s.selectedProductId = p.id;
            s.selectedColor = p.variants.get(idx).color;

            // размеры
            List<String> sizes = (p.sizes == null || p.sizes.isEmpty())
                    ? List.of("39","40","41","42","43","44","45","46")
                    : p.sizes;

            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
            for (String sz : sizes) {
                InlineKeyboardButton b = new InlineKeyboardButton(sz);
                b.setCallbackData("size:" + sz);
                rows.add(List.of(b));
            }
            SendMessage msg = new SendMessage(String.valueOf(chatId), "Выберите размер:");
            msg.setReplyMarkup(inline(rows));
            execute(msg);

            states.put(chatId, ConversationState.SELECT_SIZE);
            return;
        }

        if (data.startsWith("size:")) {
            String size = data.substring(5);
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.selectedSize = size;

            SendMessage sm2 = new SendMessage(String.valueOf(chatId), "Отлично! Для оформления введите *ФИО*:");
            sm2.setParseMode(ParseMode.MARKDOWN);
            execute(sm2);

            states.put(chatId, ConversationState.ENTER_NAME);
            return;
        }

        if (data.equals("order:confirm")) { finalizeOrder(chatId); return; }

        if (data.equals("del:SDEK") || data.equals("del:YANDEX")) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.deliveryType = data.endsWith("SDEK") ? DeliveryType.SDEK : DeliveryType.YANDEX;

            if (s.deliveryType == DeliveryType.SDEK) sendText(chatId, "Введите город и адрес/ПВЗ (одной строкой):");
            else sendText(chatId, "Введите адрес доставки. Затем пришлите комментарий для курьера (при необходимости).");

            states.put(chatId, ConversationState.ENTER_DELIVERY_FIELDS);
        }
    }

    /* ===================== Оформление ===================== */

    private void proceedFlow(long chatId, String text) throws Exception {
        ConversationState st = states.getOrDefault(chatId, ConversationState.IDLE);
        Session s = sessions.computeIfAbsent(chatId, k -> new Session());
        String chatIdStr = String.valueOf(chatId);

        switch (st) {
            case ENTER_NAME -> {
                s.fio = text.trim();
                sendText(chatId, "Введите телефон в формате +7XXXXXXXXXX или 8XXXXXXXXXX:");
                states.put(chatId, ConversationState.ENTER_PHONE);
            }
            case ENTER_PHONE -> {
                if (!PHONE_RU.matcher(text.trim()).matches()) {
                    sendText(chatId, "Некорректный формат. Пример: +79991234567");
                    return;
                }
                s.phone = text.trim();

                InlineKeyboardButton b1 = new InlineKeyboardButton("СДЭК"); b1.setCallbackData("del:SDEK");
                InlineKeyboardButton b2 = new InlineKeyboardButton("Яндекс.Доставка"); b2.setCallbackData("del:YANDEX");

                SendMessage sm = new SendMessage(chatIdStr, "Выберите способ доставки:");
                sm.setReplyMarkup(inline(List.of(List.of(b1), List.of(b2))));
                execute(sm);

                states.put(chatId, ConversationState.CHOOSE_DELIVERY);
            }
            case ENTER_DELIVERY_FIELDS -> {
                if (s.deliveryType == DeliveryType.SDEK) {
                    s.city = text.trim();
                    s.address = text.trim();
                } else {
                    if (s.address == null) {
                        s.address = text.trim();
                        sendText(chatId, "Комментарий для курьера (или «-»):");
                        return;
                    }
                    s.courierComment = "-".equals(text) ? null : text.trim();
                }

                if (s.selectedProductId == null) {
                    var list = products.listActive();
                    if (!list.isEmpty()) s.selectedProductId = list.get(0).id;
                }
                if (s.selectedProductId == null || products.find(s.selectedProductId).isEmpty()) {
                    sendText(chatId, "Сессия устарела. Пожалуйста, откройте «Каталог» и начните заново.");
                    return;
                }

                Product p = products.find(s.selectedProductId).get();
                String review = """
                        Проверьте данные:
                        • Модель: %s
                        • Цвет/размер: %s / %s
                        • ФИО: %s
                        • Телефон: %s
                        • Доставка: %s
                        • Адрес/ПВЗ: %s
                        Цена к оплате: %d ₽
                        """.formatted(
                        p.name, s.selectedColor, s.selectedSize, s.fio, s.phone, s.deliveryType,
                        s.address == null ? "—" : s.address + (s.pvz == null ? "" : "; ПВЗ: " + s.pvz),
                        p.price > 0 ? p.price : cfg.priceRub()
                );

                InlineKeyboardButton ok = new InlineKeyboardButton("Оформить предзаказ ✅");
                ok.setCallbackData("order:confirm");

                SendMessage reviewMsg = new SendMessage(chatIdStr, review);
                reviewMsg.setReplyMarkup(inline(List.of(List.of(ok))));
                execute(reviewMsg);

                states.put(chatId, ConversationState.REVIEW);
            }
            default -> sendMainMenu(chatId);
        }
    }

    private void finalizeOrder(long chatId) throws Exception {
        Session s = sessions.get(chatId);
        if (s == null || s.selectedProductId == null || products.find(s.selectedProductId).isEmpty()) {
            sendText(chatId, "Сессия истекла. Начните заново: «Каталог».");
            return;
        }
        if (orders.countNonFailed() >= cfg.dropLimit()) {
            sendText(chatId, "❌ Предзаказ закрыт: достигнут лимит " + cfg.dropLimit() + " пар.");
            return;
        }

        Product p = products.find(s.selectedProductId).get();

        Order o = new Order();
        o.telegramId = chatId;
        o.productId = p.id;
        o.color = s.selectedColor;
        o.size = s.selectedSize;
        o.deliveryType = s.deliveryType;
        o.city = s.city;
        o.address = s.address;
        o.pvz = s.pvz;
        o.courierComment = s.courierComment;
        o.status = OrderStatus.WAITING_PAYMENT;
        o.paymentStatus = "PENDING";
        o.amount = p.price > 0 ? p.price : cfg.priceRub();

        long id = orders.create(o);
        o.id = id;

        String leadId = bitrix.createLead(o, s.fio, s.phone);
        if (leadId != null) orders.setLead(id, leadId);

        String url = rk.buildPaymentUrl(o);
        sendText(chatId, "Перейдите к оплате по ссылке (" + o.amount + " ₽):\n" + url);
        sendText(chatId, "После успешной оплаты вы получите подтверждение здесь. Спасибо!");

        states.put(chatId, ConversationState.PAYMENT_LINK_ISSUED);
        s.draftOrderId = id;
    }

    /* ===================== Служебные ===================== */

    public void notifyPaymentReceived(long chatId, long orderId) {
        try {
            sendText(chatId, "✅ Оплата получена! Заказ №" + orderId +
                    " принят. Статус обновится в разделе «Мои заказы». Спасибо, что стали частью первого дропа Winrun 👟");
        } catch (Exception ignore) { }
    }

    private void showMyOrders(long chatId) throws Exception {
        List<Order> list = orders.listByUser(chatId);
        if (list.isEmpty()) { sendText(chatId, "У вас пока нет заказов."); return; }
        StringBuilder sb = new StringBuilder("Ваши последние заказы:\n");
        for (Order o : list) {
            OrderStatus st = o.status;
            if (o.bitrixLeadId != null) {
                OrderStatus external = bitrix.fetchLeadStatus(o.bitrixLeadId);
                if (external != null) st = external;
            }
            String nice = switch (st) {
                case WAITING_PAYMENT -> "🕓 Ожидание оплаты";
                case PAID_ACCEPTED   -> "💰 Оплачено, заказ принят";
                case PREPARING       -> "📦 Готовится к отправке";
                case SHIPPED         -> "🚚 Отправлен";
                case DELIVERED       -> "✅ Доставлен";
            };
            sb.append("• №").append(o.id).append(": ").append(nice).append("\n");
        }
        sendText(chatId, sb.toString());
    }

    private InlineKeyboardMarkup inline(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(rows);
        return kb;
    }

    private void sendText(long chatId, String text) throws TelegramApiException {
        SendMessage sm = new SendMessage(String.valueOf(chatId), text);
        sm.setReplyMarkup(mainMenu());
        execute(sm);
    }

    private ReplyKeyboard mainMenu() {
        ReplyKeyboardMarkup k = new ReplyKeyboardMarkup();
        k.setResizeKeyboard(true);

        KeyboardRow row1 = new KeyboardRow();
        row1.add(new KeyboardButton("👟 Каталог"));

        KeyboardRow row2 = new KeyboardRow();
        row2.add(new KeyboardButton("📦 Мои заказы"));

        KeyboardRow row3 = new KeyboardRow();
        row3.add(new KeyboardButton("ℹ️ О коллекции"));
        row3.add(new KeyboardButton("💬 Поддержка"));

        List<KeyboardRow> rows = new ArrayList<>();
        rows.add(row1); rows.add(row2); rows.add(row3);
        k.setKeyboard(rows);
        return k;
    }

    private void sendMainMenu(long chatId) throws TelegramApiException {
        ReplyKeyboardMarkup k = (ReplyKeyboardMarkup) mainMenu();
        SendMessage sm = new SendMessage(String.valueOf(chatId), "Добро пожаловать в Winrun! Выберите раздел:");
        sm.setReplyMarkup(k);
        execute(sm);

        states.put(chatId, ConversationState.IDLE);
        sessions.put(chatId, new Session());
    }
}