package com.winrun.repo;

import com.winrun.db.Database;
import com.winrun.model.DeliveryType;
import com.winrun.model.Order;
import com.winrun.model.OrderStatus;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderRepo {
    private final Database db;
    public OrderRepo(Database db){ this.db=db; }

    public long create(Order o) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
           INSERT INTO orders(telegram_id,product_id,color,size,delivery_type,city,address,pvz,courier_comment,
                              status,payment_status,amount,payment_url,robokassa_inv_id,bitrix_lead_id)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, Statement.RETURN_GENERATED_KEYS)) {
            int i=1;
            ps.setLong(i++, o.telegramId);
            ps.setInt(i++, o.productId);
            ps.setString(i++, o.color);
            ps.setString(i++, o.size);
            ps.setString(i++, o.deliveryType==null?null:o.deliveryType.name());
            ps.setString(i++, o.city);
            ps.setString(i++, o.address);
            ps.setString(i++, o.pvz);
            ps.setString(i++, o.courierComment);
            ps.setString(i++, o.status.name());
            ps.setString(i++, o.paymentStatus);
            ps.setInt(i++, o.amount);
            ps.setString(i++, o.paymentUrl);
            if (o.robokassaInvId==null) ps.setNull(i++, Types.INTEGER); else ps.setLong(i++, o.robokassaInvId);
            ps.setString(i, o.bitrixLeadId);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    public void setPayment(long orderId, String status, String url, Long invId) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
            UPDATE orders SET payment_status=?, payment_url=?, robokassa_inv_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
        """)) {
            ps.setString(1,status);
            ps.setString(2,url);
            if (invId==null) ps.setNull(3,Types.INTEGER); else ps.setLong(3, invId);
            ps.setLong(4,orderId);
            ps.executeUpdate();
        }
    }

    public void setPaid(long orderId) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
            UPDATE orders SET payment_status='PAID', status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
        """)) {
            ps.setString(1, OrderStatus.PAID_ACCEPTED.name());
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    public void setLead(long orderId, String leadId) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("UPDATE orders SET bitrix_lead_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            ps.setString(1, leadId);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        }
    }

    public Optional<Order> find(long id) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("SELECT * FROM orders WHERE id=?")) {
            ps.setLong(1,id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        }
    }

    public Optional<Order> findByInvoice(long invId) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("SELECT * FROM orders WHERE robokassa_inv_id=?")) {
            ps.setLong(1,invId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        }
    }

    public List<Order> listByUser(long telegramId) throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
            SELECT * FROM orders WHERE telegram_id=? ORDER BY created_at DESC LIMIT 10
        """)) {
            ps.setLong(1,telegramId);
            ResultSet rs = ps.executeQuery();
            List<Order> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }

    public int countNonFailed() throws SQLException {
        try (PreparedStatement ps = db.get().prepareStatement("""
            SELECT count(*) FROM orders WHERE payment_status IN ('PENDING','PAID')
        """)) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Order map(ResultSet rs) throws SQLException {
        Order o = new Order();
        o.id = rs.getLong("id");
        o.telegramId = rs.getLong("telegram_id");
        o.productId = rs.getInt("product_id");
        o.color = rs.getString("color");
        o.size = rs.getString("size");
        String dt = rs.getString("delivery_type");
        o.deliveryType = dt==null?null: DeliveryType.valueOf(dt);
        o.city = rs.getString("city");
        o.address = rs.getString("address");
        o.pvz = rs.getString("pvz");
        o.courierComment = rs.getString("courier_comment");
        o.status = OrderStatus.valueOf(rs.getString("status"));
        o.paymentStatus = rs.getString("payment_status");
        o.amount = rs.getInt("amount");
        o.paymentUrl = rs.getString("payment_url");
        long inv = rs.getLong("robokassa_inv_id");
        o.robokassaInvId = rs.wasNull()?null:inv;
        o.bitrixLeadId = rs.getString("bitrix_lead_id");
        return o;
    }
}