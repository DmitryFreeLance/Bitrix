package com.winrun;

import com.winrun.bot.WinrunBot;
import com.winrun.db.Database;
import com.winrun.integrations.BitrixClient;
import com.winrun.integrations.RobokassaService;
import com.winrun.repo.OrderRepo;
import com.winrun.repo.ProductRepo;
import com.winrun.web.WebServer;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class App {
    public static void main(String[] args) throws Exception {
        Config cfg = new Config();
        Database db = new Database(cfg);
        db.initSchema();

        ProductRepo productRepo = new ProductRepo(db);
        OrderRepo orderRepo = new OrderRepo(db);

        BitrixClient bitrix = new BitrixClient(cfg, productRepo, orderRepo);
        RobokassaService robokassa = new RobokassaService(cfg, orderRepo, bitrix);

        // Telegram bot
        WinrunBot bot = new WinrunBot(cfg, productRepo, orderRepo, bitrix, robokassa);
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bot);

        // Web server for payment callbacks
        WebServer server = new WebServer(cfg, robokassa, orderRepo, bot);
        server.start();

        System.out.println("Winrun bot started. Health: " + cfg.basePublicUrl() + "/health");
    }
}