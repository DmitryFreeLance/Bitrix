package com.winrun.db;

import com.winrun.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public class Database {
    private final Config cfg;
    private Connection conn;

    public Database(Config cfg) { this.cfg = cfg; }

    public synchronized Connection get() throws SQLException {
        if (conn == null || conn.isClosed()) {
            try {
                Path p = Path.of(cfg.sqlitePath());
                if (p.getParent()!=null) Files.createDirectories(p.getParent());
            } catch (Exception ignored) {}
            conn = DriverManager.getConnection("jdbc:sqlite:" + cfg.sqlitePath());
            conn.createStatement().execute("PRAGMA foreign_keys=ON");
        }
        return conn;
    }

    public void initSchema() throws SQLException {
        try (Statement st = get().createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS users(
                  telegram_id INTEGER PRIMARY KEY,
                  full_name TEXT,
                  phone TEXT,
                  created_at TEXT DEFAULT CURRENT_TIMESTAMP
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS products(
                  id INTEGER PRIMARY KEY,
                  name TEXT NOT NULL,
                  description TEXT,
                  price INTEGER NOT NULL,
                  image_url TEXT,
                  bitrix_id TEXT,
                  colors_csv TEXT,
                  sizes_csv TEXT,
                  variants_json TEXT,
                  active INTEGER DEFAULT 1
                )""");

            st.execute("""
                CREATE TABLE IF NOT EXISTS orders(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  telegram_id INTEGER NOT NULL,
                  product_id INTEGER NOT NULL,
                  color TEXT,
                  size TEXT,
                  delivery_type TEXT,
                  city TEXT,
                  address TEXT,
                  pvz TEXT,
                  courier_comment TEXT,
                  status TEXT,
                  payment_status TEXT,
                  amount INTEGER NOT NULL,
                  payment_url TEXT,
                  robokassa_inv_id INTEGER,
                  bitrix_lead_id TEXT,
                  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                  updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                  FOREIGN KEY(product_id) REFERENCES products(id)
                )""");
        }

        // Обновление старой БД: попытка добавить недостающий столбец variants_json
        try (Statement st = get().createStatement()) {
            st.execute("ALTER TABLE products ADD COLUMN variants_json TEXT");
        } catch (SQLException ignore) { /* уже есть */ }
    }
}