package com.winrun.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winrun.db.Database;
import com.winrun.model.Product;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class ProductRepo {
    private final Database db;
    private final ObjectMapper om = new ObjectMapper();

    public ProductRepo(Database db){ this.db=db; }

    public List<Product> listActive() throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement(
                "SELECT id,name,description,price,image_url,bitrix_id,colors_csv,sizes_csv,variants_json FROM products WHERE active=1 ORDER BY id")) {
            ResultSet rs = ps.executeQuery();
            List<Product> out = new ArrayList<>();
            while (rs.next()){
                out.add(map(rs));
            }
            return out;
        }
    }

    public Optional<Product> find(int id) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement(
                "SELECT id,name,description,price,image_url,bitrix_id,colors_csv,sizes_csv,variants_json FROM products WHERE id=?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        }
    }

    public void upsert(Product p) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
            INSERT INTO products(id,name,description,price,image_url,bitrix_id,colors_csv,sizes_csv,variants_json,active)
            VALUES(?,?,?,?,?,?,?,?,?,1)
            ON CONFLICT(id) DO UPDATE SET
              name=excluded.name,
              description=excluded.description,
              price=excluded.price,
              image_url=excluded.image_url,
              bitrix_id=excluded.bitrix_id,
              colors_csv=excluded.colors_csv,
              sizes_csv=excluded.sizes_csv,
              variants_json=excluded.variants_json,
              active=1
        """)) {
            ps.setInt(1,p.id);
            ps.setString(2,p.name);
            ps.setString(3,p.description);
            ps.setInt(4,p.price);
            ps.setString(5,p.imageUrl);
            ps.setString(6,p.bitrixId);
            ps.setString(7,listToCsv(p.colors));
            ps.setString(8,listToCsv(p.sizes));
            ps.setString(9, p.variantsJson);
            ps.executeUpdate();
        }
    }

    // Добавить в ProductRepo
    public void pruneExcept(java.util.Collection<Integer> keepIds) throws SQLException {
        // Если список пуст — выключаем всё
        if (keepIds == null || keepIds.isEmpty()) {
            try (Statement st = db.get().createStatement()) {
                st.executeUpdate("UPDATE products SET active=0");
            }
            return;
        }

        // Построим плейсхолдеры (?, ?, ?, ...)
        String placeholders = keepIds.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "UPDATE products SET active = CASE WHEN id IN (" + placeholders + ") THEN 1 ELSE 0 END";

        try (PreparedStatement ps = db.get().prepareStatement(sql)) {
            int i = 1;
            for (Integer id : keepIds) {
                ps.setInt(i++, id);
            }
            ps.executeUpdate();
        }
    }

    private Product map(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.id = rs.getInt("id");
        p.name = rs.getString("name");
        p.description = rs.getString("description");
        p.price = rs.getInt("price");
        p.imageUrl = rs.getString("image_url");
        p.bitrixId = rs.getString("bitrix_id");
        p.colors = csvToList(rs.getString("colors_csv"));
        p.sizes  = csvToList(rs.getString("sizes_csv"));
        p.variantsJson = rs.getString("variants_json");
        try {
            if (p.variantsJson != null && !p.variantsJson.isBlank()) {
                p.variants = om.readValue(p.variantsJson, new TypeReference<List<Product.Variant>>(){});
            } else {
                p.variants = Collections.emptyList();
            }
        } catch (Exception e) {
            p.variants = Collections.emptyList();
        }
        return p;
    }

    private static List<String> csvToList(String s){
        if (s==null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x->!x.isEmpty()).collect(Collectors.toList());
    }
    private static String listToCsv(List<String> list){
        if (list==null) return "";
        return String.join(",", list);
    }

    /* ==== Одноразовый сидинг стартового каталога ==== */
    public void seedIfEmpty() throws Exception {
        if (!listActive().isEmpty()) return;

        String json = """
        [
          {
            "id": 1001,
            "name": "Winrun Axis",
            "description": "Кеды с точкой равновесия — строгие линии и универсальность. Axis держит баланс между спортивной динамикой и кэжуальной простотой.",
            "price": 8990,
            "variants": [
              {"color": "белый/чёрный", "image": "axis1.jpg"},
              {"color": "хаки/бежевый", "image": "axis2.jpg"},
              {"color": "серый/белый", "image": "axis3.jpg"},
              {"color": "чёрный/белый", "image": "axis4.jpg"}
            ]
          },
          {
            "id": 1002,
            "name": "Winrun Urban",
            "description": "Лаконичные кеды на каждый день. Urban Classic — сдержанная эстетика и комфорт. Чистая форма легко «садится» к джинсам, брюкам и шортам.",
            "price": 8990,
            "variants": [
              {"color": "бежевый/зелёный", "image": "urban1.jpg"},
              {"color": "чёрный",          "image": "urban2.jpg"},
              {"color": "белый",           "image": "urban3.jpg"},
              {"color": "серый/белый",     "image": "urban4.jpg"}
            ]
          },
          {
            "id": 1003,
            "name": "Winrun Flow",
            "description": "Мужские кроссовки для активного дня: гибкая посадка, плавный перекат и уверенное сцепление — от офиса до прогулок.",
            "price": 8990,
            "variants": [
              {"color": "светло-бежевый/зелёный", "image": "flow1.jpg"},
              {"color": "оранжевый-чёрный",       "image": "flow2.jpg"},
              {"color": "чёрный-серый",           "image": "flow3.jpg"},
              {"color": "белый",                  "image": "flow4.jpg"}
            ]
          },
          {
            "id": 1004,
            "name": "Winrun Rise",
            "description": "Rise вдохновляют на движение к целям. Лёгкая амортизация смягчает шаг, продуманная посадка поддерживает стопу в активном дне.",
            "price": 8990,
            "variants": [
              {"color": "коричневый+бежевый+светло-бежевый", "image": "rise1.jpg"},
              {"color": "чёрный+серый",                      "image": "rise2.jpg"},
              {"color": "светло-коричневый/светло-серый",    "image": "rise3.jpg"},
              {"color": "белый+светло-серый",                "image": "rise4.jpg"}
            ]
          },
          {
            "id": 1005,
            "name": "Winrun Shift",
            "description": "Shift — про свободу сценариев: офис, прогулка, короткая тренировка. Стабильная опора и комфортная фиксация дают уверенность в каждом шаге.",
            "price": 8990,
            "variants": [
              {"color": "светло-коричневый/светло-бежевый+молочный", "image": "shift1.jpg"},
              {"color": "светло-бежевый+бордо",                      "image": "shift2.jpg"},
              {"color": "белый",                                     "image": "shift3.jpg"},
              {"color": "чёрный",                                    "image": "shift4.jpg"}
            ]
          }
        ]
        """;

        // читаем как List<Map>, чтобы не зависеть от Product.Variant в момент сидинга
        var root = om.readTree(json);
        for (var node : root) {
            Product p = new Product();
            p.id = node.get("id").asInt();
            p.name = node.get("name").asText("");
            p.description = node.get("description").asText("");
            p.price = node.get("price").asInt(8990);
            p.imageUrl = ""; // не используется
            p.colors = List.of(); // не используем
            p.sizes  = List.of("39","40","41","42","43","44","45","46");
            p.variantsJson = om.writeValueAsString(node.get("variants"));
            upsert(p);
        }
    }
}