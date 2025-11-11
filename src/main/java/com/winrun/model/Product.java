package com.winrun.model;

import java.util.List;

public class Product {
    public int id;
    public String name;
    public String description;
    public int price;
    /** НЕ используем для карточки (теперь картинка в вариантах), оставлено для совместимости */
    public String imageUrl;
    public String bitrixId;

    /** опциональные поля из старой логики — размеры используем */
    public List<String> colors;
    public List<String> sizes;

    /** JSON-хранилище вариантов (в БД) и расшифрованные варианты в рантайме */
    public String variantsJson;
    public List<Variant> variants;

    /** Один вариант модели: цвет + картинка (лучше URL) */
    public static class Variant {
        public String color;
        public String image;
    }
}