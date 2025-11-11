package com.winrun.model;

public class Session {
    // Выбор модели/варианта
    public Integer selectedModelId;   // id модели (Product.id)
    public Integer variantIndex;      // текущий индекс варианта (цвета)
    public String  selectedColor;     // выбранный цвет (из варианта)

    // Сообщение карточки модели (для редактирования фото/подписи)
    public Integer lastCardMessageId; // messageId отправленного фото/сообщения
    public Boolean lastCardIsPhoto;   // было фото (true) или текст (false)

    // Дальше — как было
    public Integer selectedProductId; // для заказа используем id модели
    public String selectedSize;
    public String fio;
    public String phone;
    public DeliveryType deliveryType;
    public String city;
    public String address;
    public String pvz;
    public String courierComment;
    public Long draftOrderId;
}