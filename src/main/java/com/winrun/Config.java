package com.winrun;

import io.github.cdimascio.dotenv.Dotenv;

public class Config {
    private final Dotenv env = Dotenv.configure().ignoreIfMissing().load();

    /* === Telegram === */
    public String botToken()     { return env.get("TELEGRAM_BOT_TOKEN", "8314884816:AAGZpXjPvASyl3RYLiSLQjMGothXQ81aZTc"); }
    public String botUsername()  { return env.get("TELEGRAM_BOT_USERNAME", "WinrunDrop_bot"); }

    /* === App === */
    public int    serverPort()   { return Integer.parseInt(env.get("SERVER_PORT", "8080")); }
    public String basePublicUrl(){ return env.get("BASE_PUBLIC_URL", "http://localhost:8080"); }
    public String sqlitePath()   { return env.get("SQLITE_PATH", "winrun.db"); }

    /* === Drop settings === */
    public int    dropLimit()    { return Integer.parseInt(env.get("DROP_LIMIT", "300")); }
    public int    priceRub()     { return Integer.parseInt(env.get("PRICE_RUB", "8990")); }

    /* === Bitrix (входящий вебхук) === */
    public String bitrixBase()        { return env.get("BITRIX_WEBHOOK_BASE", ""); }
    public String bitrixLeadSource()  { return env.get("BITRIX_LEAD_SOURCE_ID", "WEB"); }
    public String bitrixTag()         { return env.get("BITRIX_TAG", "Предзаказ — Дроп 1 (300 пар)"); }

    /* === Изображения ===
       Базовый путь к локальным картинкам (по умолчанию — текущая рабочая директория).
       Если variant.image = "axis1.jpg", итоговый путь будет IMAGES_BASE_PATH/axis1.jpg
    */
    public String imagesBasePath()    { return env.get("IMAGES_BASE_PATH", "."); }

    /* === Совместимость (если где-то в коде ещё используется) === */
    public String bitrixProductXmlId(){ return env.get("BITRIX_PRODUCT_XML_ID", "212"); }

    /* === Robokassa === */
    public String rkLogin()   { return env.get("ROBOKASSA_LOGIN", ""); }
    public String rkPass1()   { return env.get("ROBOKASSA_PASSWORD1", ""); }
    public String rkPass2()   { return env.get("ROBOKASSA_PASSWORD2", ""); }
    public String rkAlg()     { return env.get("ROBOKASSA_SIGNATURE_ALG", "MD5"); }
    public boolean rkIsTest() { return Boolean.parseBoolean(env.get("ROBOKASSA_IS_TEST", "true")); }

    /* === Misc === */
    public String paymentDescription() { return env.get("PAYMENT_DESCRIPTION", "Winrun preorder (доставка включена)"); }
    public String supportUsername()    { return env.get("SUPPORT_USERNAME", "@winrun_support"); }
}
