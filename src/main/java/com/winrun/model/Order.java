package com.winrun.model;

public class Order {
    public long id;
    public long telegramId;
    public int productId;
    public String color;
    public String size;
    public DeliveryType deliveryType;
    public String city;
    public String address;
    public String pvz;
    public String courierComment;
    public OrderStatus status;
    public String paymentStatus; // PENDING/PAID/FAILED
    public int amount;
    public String paymentUrl;
    public Long robokassaInvId;
    public String bitrixLeadId;
}