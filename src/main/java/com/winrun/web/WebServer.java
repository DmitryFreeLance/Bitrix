package com.winrun.web;

import com.winrun.Config;
import com.winrun.bot.WinrunBot;
import com.winrun.integrations.RobokassaService;
import com.winrun.model.Order;
import com.winrun.repo.OrderRepo;
import io.javalin.Javalin;

import java.util.Optional;

public class WebServer {
    private final Config cfg;
    private final RobokassaService rk;
    private final OrderRepo orders;
    private final WinrunBot bot;

    public WebServer(Config cfg, RobokassaService rk, OrderRepo orders, WinrunBot bot){
        this.cfg=cfg; this.rk=rk; this.orders=orders; this.bot=bot;
    }

    public void start(){
        Javalin app = Javalin.create(c -> c.showJavalinBanner = false).start(cfg.serverPort());

        app.get("/health", ctx -> ctx.result("OK"));

        // Robokassa: технический result (сервер-сервер)
        app.get("/robokassa/result", ctx -> {
            String outSum = ctx.queryParam("OutSum");
            String invId  = ctx.queryParam("InvId");
            String sig    = ctx.queryParam("SignatureValue");
            if (outSum==null || invId==null || sig==null) { ctx.status(400).result("Bad params"); return; }
            try {
                if (rk.verifyResult(outSum, invId, sig)){
                    long oid = Long.parseLong(invId);
                    rk.markPaid(oid);
                    orders.find(oid).ifPresent(o -> bot.notifyPaymentReceived(o.telegramId, o.id));
                    ctx.result("OK"+invId);
                } else {
                    ctx.status(400).result("Invalid signature");
                }
            } catch (Exception e){
                ctx.status(500).result("Error");
            }
        });

        // Человеческие success/fail редиректы
        app.get("/robokassa/success", ctx -> ctx.result("Оплата принята, спасибо! Можете вернуться в Telegram."));
        app.get("/robokassa/fail", ctx -> ctx.result("Оплата не прошла или отменена."));
    }
}