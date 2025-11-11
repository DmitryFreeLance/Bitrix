package com.winrun.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winrun.Config;
import com.winrun.model.Order;
import com.winrun.model.OrderStatus;
import com.winrun.model.Product;
import com.winrun.repo.OrderRepo;
import com.winrun.repo.ProductRepo;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.Locale;
import java.util.Objects;

public class BitrixClient {
    private final Config cfg;
    private final ProductRepo productRepo;
    private final OrderRepo orderRepo;
    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    public BitrixClient(Config cfg, ProductRepo productRepo, OrderRepo orderRepo){
        this.cfg=cfg; this.productRepo=productRepo; this.orderRepo=orderRepo;
    }

    public boolean hasConfig(){ return cfg.bitrixBase()!=null && !cfg.bitrixBase().isBlank(); }

    /* ======================= КАТАЛОГ ======================= */

    /**
     * Тянем один нужный товар по внешнему коду (XML_ID).
     * Источник XML_ID: переменная окружения BITRIX_PRODUCT_XML_ID, иначе дефолт "212".
     * После импорта — выключаем (active=0) все остальные товары локально.
     */
    public void syncCatalog() {
        if (!hasConfig()) return;
        try {
            String xmlId = System.getenv("BITRIX_PRODUCT_XML_ID");
            if (xmlId == null || xmlId.isBlank()) xmlId = "212";

            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(cfg.bitrixBase()+"catalog.product.list"))
                    .newBuilder()
                    .addQueryParameter("filter[=XML_ID]", xmlId)
                    .addQueryParameter("filter[ACTIVE]", "Y")
                    .addQueryParameter("select[]", "ID")
                    .addQueryParameter("select[]", "NAME")
                    .addQueryParameter("select[]", "DESCRIPTION")
                    .addQueryParameter("select[]", "XML_ID")
                    .addQueryParameter("select[]", "DETAIL_PICTURE")
                    .addQueryParameter("select[]", "PREVIEW_PICTURE")
                    .build();

            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body()==null) {
                    productRepoPruneAllSafe();
                    return;
                }
                JsonNode root = om.readTree(resp.body().string());
                JsonNode res = root.get("result");
                if (res == null || !res.isArray() || res.size()==0) {
                    productRepoPruneAllSafe();
                    return;
                }

                JsonNode it = res.get(0);

                Product p = new Product();
                p.id = it.path("ID").asInt();
                p.bitrixId = it.path("ID").asText();
                p.name = it.path("NAME").asText("");
                p.description = it.has("DESCRIPTION") ? it.get("DESCRIPTION").asText("") : "";
                p.price = cfg.priceRub();
                // imageUrl оставляем пустым — картинку будем скачивать байтами при показе
                p.imageUrl = "";

                // Дефолтные опции (как в приложенном коде бота)
                p.colors = List.of("Белый", "Чёрный", "Серый");
                p.sizes  = List.of("39","40","41","42","43","44","45","46");

                productRepo.upsert(p);
                productRepo.pruneExcept(Set.of(p.id));
            }
        } catch (Exception ignore) {
            try { productRepoPruneAllSafe(); } catch (Exception e) { /* ignore */ }
        }
    }

    private void productRepoPruneAllSafe() throws Exception {
        productRepo.pruneExcept(Collections.emptySet());
    }

    /**
     * Скачивает байты основной картинки товара.
     * Порядок попыток: DETAIL_PICTURE → PREVIEW_PICTURE.
     * Возвращает null, если ничего не найдено.
     */
    public byte[] downloadMainImageBytes(int productId) throws IOException {
        // 1) DETAIL_PICTURE
        byte[] bytes = tryDownloadField(productId, "DETAIL_PICTURE");
        if (bytes != null && bytes.length > 0) return bytes;

        // 2) PREVIEW_PICTURE
        bytes = tryDownloadField(productId, "PREVIEW_PICTURE");
        if (bytes != null && bytes.length > 0) return bytes;

        return null;
    }

    private byte[] tryDownloadField(int productId, String fieldName) throws IOException {
        HttpUrl url = Objects.requireNonNull(
                        HttpUrl.parse(cfg.bitrixBase() + "catalog.product.download")
                ).newBuilder()
                .addQueryParameter("productId", String.valueOf(productId))
                .addQueryParameter("fieldName", fieldName)
                .build();

        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                return resp.body().bytes();
            }
        }
        return null;
    }

    /* ======================= ЛИДЫ ======================= */

    /** Создать лид в Bitrix24 */
    public String createLead(Order o, String fio, String phone) throws IOException {
        if (!hasConfig()) return null;
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("TITLE", "Winrun предзаказ ("+o.id+")");
        fields.put("NAME", fio==null?"":fio);
        fields.put("PHONE", List.of(Map.of("VALUE", phone==null?"":phone, "VALUE_TYPE", "WORK")));
        fields.put("SOURCE_ID", cfg.bitrixLeadSource());
        fields.put("OPENED", "Y");
        fields.put("COMMENTS", "Модель/цвет/размер: "+o.productId+" / "+o.color+" / "+o.size+
                "\nДоставка: "+o.deliveryType+"; "+(o.address==null?"":o.address)+
                (o.pvz==null?"":"; ПВЗ: "+o.pvz)+
                (o.courierComment==null?"":"; Комментарий: "+o.courierComment)+
                "\nСтатус оплаты: "+o.paymentStatus);
        fields.put("TAGS", cfg.bitrixTag()); // если поле доступно на портале

        RequestBody body = RequestBody.create(new ObjectMapper().writeValueAsBytes(Map.of("fields", fields)), MediaType.parse("application/json"));
        Request req = new Request.Builder().url(cfg.bitrixBase()+"crm.lead.add.json").post(body).build();
        try (Response resp = http.newCall(req).execute()){
            if (!resp.isSuccessful() || resp.body()==null) return null;
            JsonNode root = om.readTree(resp.body().string());
            JsonNode id = root.get("result");
            return id==null?null:id.asText();
        }
    }

    /** Обновить статус лида (вызвать после успешной оплаты) */
    public boolean updateLeadStatus(String leadId, String statusId) {
        if (!hasConfig() || leadId==null || leadId.isBlank()) return false;
        try {
            RequestBody body = RequestBody.create(
                    om.writeValueAsBytes(Map.of("id", leadId, "fields", Map.of("STATUS_ID", statusId))),
                    MediaType.parse("application/json")
            );
            Request req = new Request.Builder().url(cfg.bitrixBase()+"crm.lead.update.json").post(body).build();
            try (Response resp = http.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Приводим STATUS_ID к UX-статусам бота */
    public OrderStatus fetchLeadStatus(String leadId){
        if (!hasConfig() || leadId==null) return null;
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(cfg.bitrixBase()+"crm.lead.get.json"))
                    .newBuilder().addQueryParameter("id", leadId).build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()){
                if (!resp.isSuccessful() || resp.body()==null) return null;
                JsonNode data = om.readTree(resp.body().string()).get("result");
                if (data==null) return null;
                String st = data.path("STATUS_ID").asText("NEW").toUpperCase(Locale.ROOT);
                return switch (st) {
                    case "NEW", "PREPAYMENT_INVOICE" -> OrderStatus.WAITING_PAYMENT;
                    case "IN_PROCESS"                 -> OrderStatus.PAID_ACCEPTED;
                    case "PREPARATION"                -> OrderStatus.PREPARING;
                    case "DELIVERY", "WON"            -> OrderStatus.SHIPPED;
                    case "FINAL_SUCCESS"              -> OrderStatus.DELIVERED;
                    default                           -> OrderStatus.PAID_ACCEPTED;
                };
            }
        } catch (Exception e){ return null; }
    }
}