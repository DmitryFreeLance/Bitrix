package com.winrun.integrations;

import com.winrun.Config;
import com.winrun.model.Order;
import com.winrun.repo.OrderRepo;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Locale;

public class RobokassaService {
    private final Config cfg;
    private final OrderRepo orderRepo;
    private final BitrixClient bitrix;

    public RobokassaService(Config cfg, OrderRepo orderRepo, BitrixClient bitrix){
        this.cfg=cfg; this.orderRepo=orderRepo; this.bitrix=bitrix;
    }

    /** Ссылка на оплату (по умолчанию Robokassa классический флоу) */
    public String buildPaymentUrl(Order o) throws Exception {
        long invId = o.id; // используем ID заказа как InvId
        String outSum = String.valueOf(o.amount);
        String sign = signCreate(outSum, String.valueOf(invId));
        String base = "https://auth.robokassa.ru/Merchant/Index.aspx";
        String url = base +
                "?MerchantLogin=" + urlEnc(cfg.rkLogin()) +
                "&OutSum=" + urlEnc(outSum) +
                "&InvId=" + invId +
                "&Description=" + urlEnc(cfg.paymentDescription()) +
                "&SignatureValue=" + sign +
                "&Culture=ru&Encoding=utf-8" +
                (cfg.rkIsTest() ? "&IsTest=1" : "");
        orderRepo.setPayment(o.id, "PENDING", url, invId);
        return url;
    }

    /** Проверка подписи на result-колбэке */
    public boolean verifyResult(String outSum, String invId, String signatureValue) throws Exception {
        String expected = signResult(outSum, invId);
        return expected.equalsIgnoreCase(signatureValue);
    }

    private String signCreate(String outSum, String invId) throws Exception {
        // SignatureValue = md5|sha256(MerchantLogin:OutSum:InvId:Password1)
        String raw = cfg.rkLogin()+":"+outSum+":"+invId+":"+cfg.rkPass1();
        return digestHex(raw, cfg.rkAlg());
    }

    private String signResult(String outSum, String invId) throws Exception {
        // Для Result URL: md5|sha256(OutSum:InvId:Password2)
        String raw = outSum+":"+invId+":"+cfg.rkPass2();
        return digestHex(raw, cfg.rkAlg());
    }

    private static String digestHex(String s, String alg) throws Exception {
        String a = alg==null?"MD5":alg.trim().toUpperCase(Locale.ROOT);
        MessageDigest md = MessageDigest.getInstance(a);
        byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return String.format("%032x", new BigInteger(1, hash));
    }

    private static String urlEnc(String s){ return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }

    public void markPaid(long orderId) throws SQLException {
        orderRepo.setPaid(orderId);
    }
}