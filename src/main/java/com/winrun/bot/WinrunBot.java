package com.winrun.bot;

import com.winrun.Config;
import com.winrun.integrations.BitrixClient;
import com.winrun.integrations.RobokassaService;
import com.winrun.model.*;
import com.winrun.repo.OrderRepo;
import com.winrun.repo.ProductRepo;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
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

    // Кэш известных file_id: productId -> (variantIndex -> fileId)
    private final Map<Integer, Map<Integer, String>> photoFileIdCache = new ConcurrentHashMap<>();

    private static final Pattern PHONE_RU = Pattern.compile("^(\\+7|8)\\d{10}$");

    public WinrunBot(Config cfg, ProductRepo products, OrderRepo orders, BitrixClient bitrix, RobokassaService rk) {
        super(cfg.botToken());
        this.cfg = cfg;
        this.products = products;
        this.orders = orders;
        this.bitrix = bitrix;
        this.rk = rk;

        try { this.products.seedIfEmpty(); }
        catch (Exception e) { System.out.println("Seed catalog failed: " + e.getMessage()); }
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

    /* ==================== КАТАЛОГ: СПИСОК МОДЕЛЕЙ ==================== */
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
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(p.name);
            b.setCallbackData("model:" + p.id);
            row.add(b);
            if (row.size() == 2) { rows.add(new ArrayList<>(row)); row.clear(); }
        }
        if (!row.isEmpty()) rows.add(row);

        InlineKeyboardMarkup kb = inline(rows);
        SendMessage sm = new SendMessage(String.valueOf(chatId), "Выберите модель:");
        sm.setReplyMarkup(kb);
        execute(sm);

        // Сброс состояния карточки
        Session s = sessions.computeIfAbsent(chatId, k -> new Session());
        s.lastCardMessageId = null;
        s.lastCardIsPhoto = null;

        states.put(chatId, ConversationState.SELECT_PRODUCT);
    }

    /* ==================== КАРТОЧКА МОДЕЛИ: ПОКАЗ/РЕДАКТИРОВАНИЕ ==================== */

    private static final class MediaRef {
        final InputFile file;   // null, если картинки нет
        final boolean isUrl;    // true = http/https, false = локальный файл
        MediaRef(InputFile file, boolean isUrl) { this.file = file; this.isUrl = isUrl; }
    }

    private MediaRef resolveInputFile(String ref) {
        if (ref == null || ref.isBlank()) return new MediaRef(null, false);
        String r = ref.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return new MediaRef(new InputFile(r), true);
        }
        // локальный путь
        File f = new File(r);
        if (!f.isAbsolute()) f = new File(cfg.imagesBasePath(), r);
        if (f.exists() && f.isFile()) {
            return new MediaRef(new InputFile(f, f.getName()), false);
        }
        System.out.println("[IMG] Not found: " + f.getAbsolutePath());
        return new MediaRef(null, false);
    }

    private String buildCaption(Product p, Product.Variant v) {
        return "*" + p.name + "*\n" +
                (p.description == null || p.description.isBlank() ? "" : p.description + "\n") +
                "Цвет: " + (v.color==null? "-" : v.color) + "\n" +
                "Цена: " + (p.price > 0 ? p.price : cfg.priceRub()) + " ₽";
    }

    private InlineKeyboardMarkup colorNavKb(Product p) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (p.variants != null && p.variants.size() > 1) {
            InlineKeyboardButton prev = new InlineKeyboardButton("◀");
            prev.setCallbackData("vprev");
            InlineKeyboardButton label = new InlineKeyboardButton("Сменить цвет");
            label.setCallbackData("noop"); // некликабельно по смыслу — мы проигнорируем "noop"
            InlineKeyboardButton next = new InlineKeyboardButton("▶");
            next.setCallbackData("vnext");
            rows.add(List.of(prev, label, next));
        }
        InlineKeyboardButton pick = new InlineKeyboardButton("Выбрать ✅");
        pick.setCallbackData("pickModel");
        rows.add(List.of(pick));
        return inline(rows);
    }

    private void sendModelCard(long chatId) throws Exception {
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

        String caption = buildCaption(p, v);
        InlineKeyboardMarkup kb = colorNavKb(p);

        MediaRef mr = resolveInputFile(v.image);
        Message msg;
        if (mr.file != null) {
            SendPhoto sp = new SendPhoto(String.valueOf(chatId), mr.file);
            sp.setCaption(caption);
            sp.setParseMode(ParseMode.MARKDOWN);
            sp.setReplyMarkup(kb);
            msg = execute(sp);
            s.lastCardIsPhoto = true;

            // заодно сохраним file_id фото для дальнейшего редактирования
            String fid = extractLargestPhotoFileId(msg);
            if (fid != null) cacheFileId(p.id, idx, fid);
        } else {
            SendMessage sm = new SendMessage(String.valueOf(chatId), caption + (v.image!=null && !v.image.isBlank()? "\n(изображение: " + v.image + ")" : ""));
            sm.setParseMode(ParseMode.MARKDOWN);
            sm.setReplyMarkup(kb);
            msg = execute(sm);
            s.lastCardIsPhoto = false;
        }
        s.lastCardMessageId = msg.getMessageId();
    }

    private void editModelCard(long chatId) throws Exception {
        Session s = sessions.computeIfAbsent(chatId, k -> new Session());
        if (s.selectedModelId == null || s.lastCardMessageId == null) { sendModelCard(chatId); return; }

        var opt = products.find(s.selectedModelId);
        if (opt.isEmpty()) { showModelsList(chatId); return; }
        Product p = opt.get();
        if (p.variants == null || p.variants.isEmpty()) { showModelsList(chatId); return; }

        int idx = (s.variantIndex == null) ? 0 : s.variantIndex;
        if (idx < 0 || idx >= p.variants.size()) idx = 0;
        Product.Variant v = p.variants.get(idx);

        String caption = buildCaption(p, v);
        InlineKeyboardMarkup kb = colorNavKb(p);

        // 1) если уже знаем file_id целевой картинки — редактируем по file_id
        String knownFileId = getCachedFileId(p.id, idx);
        if (knownFileId != null) {
            try {
                InputMediaPhoto photo = new InputMediaPhoto();
                photo.setMedia(knownFileId); // file_id
                photo.setCaption(caption);
                photo.setParseMode(ParseMode.MARKDOWN);

                EditMessageMedia edit = new EditMessageMedia();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(s.lastCardMessageId);
                edit.setMedia(photo);
                edit.setReplyMarkup(kb);
                execute(edit);
                s.lastCardIsPhoto = true;
                return;
            } catch (TelegramApiException e) {
                System.out.println("Edit by file_id failed: " + e.getMessage());
            }
        }

        // 2) если есть публичный URL — редактируем URL
        MediaRef mr = resolveInputFile(v.image);
        if (mr.file != null && mr.isUrl) {
            try {
                InputMediaPhoto photo = new InputMediaPhoto();
                photo.setMedia(String.valueOf(mr.file)); // URL
                photo.setCaption(caption);
                photo.setParseMode(ParseMode.MARKDOWN);

                EditMessageMedia edit = new EditMessageMedia();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(s.lastCardMessageId);
                edit.setMedia(photo);
                edit.setReplyMarkup(kb);
                execute(edit);
                s.lastCardIsPhoto = true;
                return;
            } catch (TelegramApiException e) {
                System.out.println("Edit by URL failed: " + e.getMessage());
            }
        }

        // 3) локальный файл или картинка недоступна — fallback: один раз переотправим,
        // сохраним file_id, дальше будем редактировать по file_id без удаления
        if (mr.file != null && !mr.isUrl) {
            deleteSilently(chatId, s.lastCardMessageId);
            s.lastCardMessageId = null;

            SendPhoto sp = new SendPhoto(String.valueOf(chatId), mr.file);
            sp.setCaption(caption);
            sp.setParseMode(ParseMode.MARKDOWN);
            sp.setReplyMarkup(kb);
            Message msg = execute(sp);
            s.lastCardIsPhoto = true;
            s.lastCardMessageId = msg.getMessageId();

            String fid = extractLargestPhotoFileId(msg);
            if (fid != null) cacheFileId(p.id, idx, fid);
            return;
        }

        // 4) картинки нет — редактируем подпись/текст
        if (Boolean.TRUE.equals(s.lastCardIsPhoto)) {
            EditMessageCaption ec = new EditMessageCaption();
            ec.setChatId(String.valueOf(chatId));
            ec.setMessageId(s.lastCardMessageId);
            ec.setCaption(caption);
            ec.setParseMode(ParseMode.MARKDOWN);
            ec.setReplyMarkup(kb);
            execute(ec);
        } else {
            EditMessageText et = new EditMessageText();
            et.setChatId(String.valueOf(chatId));
            et.setMessageId(s.lastCardMessageId);
            et.setText(caption + (v.image!=null && !v.image.isBlank()? "\n(изображение: " + v.image + ")" : ""));
            et.setParseMode(ParseMode.MARKDOWN);
            et.setReplyMarkup(kb);
            execute(et);
        }
    }

    /* ==================== CALLBACKS ==================== */

    private void onCallback(CallbackQuery q) throws Exception {
        long chatId = q.getMessage().getChatId();
        String data = q.getData();

        if ("noop".equals(data)) {
            // Ничего не делаем: это просто "лейбл" в середине
            return;
        }

        if (data.startsWith("model:")) {
            int id = Integer.parseInt(data.substring(6));
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.selectedModelId = id;
            s.variantIndex = 0;
            s.lastCardMessageId = null;
            s.lastCardIsPhoto = null;
            sendModelCard(chatId);
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
            if ("vnext".equals(data)) idx = (idx + 1) % n;
            else idx = (idx - 1 + n) % n;
            s.variantIndex = idx;

            editModelCard(chatId);
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

            s.selectedProductId = p.id;                 // для заказа используем id модели
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
            InlineKeyboardMarkup kb = inline(rows);

            SendMessage msg = new SendMessage(String.valueOf(chatId), "Выберите размер:");
            msg.setReplyMarkup(kb);
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

    /* ==================== ФЛОУ ОФОРМЛЕНИЯ ==================== */

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

                InlineKeyboardButton b1 = new InlineKeyboardButton("СДЭК");
                b1.setCallbackData("del:SDEK");
                InlineKeyboardButton b2 = new InlineKeyboardButton("Яндекс.Доставка");
                b2.setCallbackData("del:YANDEX");

                InlineKeyboardMarkup kb = inline(List.of(List.of(b1), List.of(b2)));
                SendMessage sm = new SendMessage(chatIdStr, "Выберите способ доставки:");
                sm.setReplyMarkup(kb);
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
                        p.name,
                        s.selectedColor,
                        s.selectedSize,
                        s.fio,
                        s.phone,
                        s.deliveryType,
                        s.address == null ? "—" : s.address + (s.pvz == null ? "" : "; ПВЗ: " + s.pvz),
                        p.price > 0 ? p.price : cfg.priceRub()
                );

                InlineKeyboardButton ok = new InlineKeyboardButton("Оформить предзаказ ✅");
                ok.setCallbackData("order:confirm");
                InlineKeyboardMarkup kb = inline(List.of(List.of(ok)));

                SendMessage reviewMsg = new SendMessage(chatIdStr, review);
                reviewMsg.setReplyMarkup(kb);
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

    /* Публичный метод — дергает WebServer после успешной оплаты */
    public void notifyPaymentReceived(long chatId, long orderId) {
        try {
            sendText(chatId,
                    "✅ Оплата получена! Заказ №" + orderId +
                            " принят. Статус обновится в разделе «Мои заказы». " +
                            "Спасибо, что стали частью первого дропа Winrun 👟"
            );
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

    /* ==================== УТИЛИТЫ ==================== */

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

    private void deleteSilently(long chatId, Integer messageId) {
        if (messageId == null) return;
        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); }
        catch (Exception ignore) {}
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
        Session s = new Session();
        sessions.put(chatId, s);
    }

    private void cacheFileId(int productId, int variantIndex, String fileId) {
        photoFileIdCache.computeIfAbsent(productId, k -> new ConcurrentHashMap<>()).put(variantIndex, fileId);
    }
    private String getCachedFileId(int productId, int variantIndex) {
        Map<Integer, String> m = photoFileIdCache.get(productId);
        return m == null ? null : m.get(variantIndex);
    }
    private String extractLargestPhotoFileId(Message msg) {
        if (msg == null || msg.getPhoto() == null || msg.getPhoto().isEmpty()) return null;
        PhotoSize best = null;
        for (PhotoSize ps : msg.getPhoto()) {
            if (best == null || (ps.getFileSize() != null && best.getFileSize() != null && ps.getFileSize() > best.getFileSize())) {
                best = ps;
            }
        }
        return best != null ? best.getFileId() : null;
    }
}