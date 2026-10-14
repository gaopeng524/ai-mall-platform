SET NAMES utf8mb4;

-- 用户库初始化
CREATE DATABASE IF NOT EXISTS aimall_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aimall_user;
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username    VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    email       VARCHAR(100) COMMENT '邮箱',
    phone       VARCHAR(20) COMMENT '手机号',
    role        VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER' COMMENT '角色',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

INSERT IGNORE INTO users (id, username, password, role) VALUES
(1, 'admin', '$2a$10$9LU64bCi64qur8cfOA4YsunDCd//EYkDnaDGcnCSk8UL0cbQZGe9C', 'ROLE_ADMIN'),
(2, 'testuser', '$2a$10$9LU64bCi64qur8cfOA4YsunDCd//EYkDnaDGcnCSk8UL0cbQZGe9C', 'ROLE_USER');

-- 商品库初始化
CREATE DATABASE IF NOT EXISTS aimall_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aimall_product;
CREATE TABLE IF NOT EXISTS products (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '商品ID',
    name        VARCHAR(200) NOT NULL COMMENT '商品名称',
    category    VARCHAR(50) NOT NULL COMMENT '商品分类',
    price       DECIMAL(10,2) NOT NULL COMMENT '价格',
    stock       INT NOT NULL DEFAULT 0 COMMENT '库存',
    description TEXT COMMENT '商品描述',
    image_url   VARCHAR(500) COMMENT '主图',
    status      TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1-上架 0-下架',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

TRUNCATE TABLE products;
INSERT INTO products (id, name, category, price, stock, description, image_url, status) VALUES
(1, 'Nike Air Zoom Pegasus 40 跑步鞋', '运动鞋', 799.00, 100, 'Nike 经典跑步鞋，适合日常跑步与长距离训练，React 泡棉结合双 Zoom Air 气垫，轻量透气，支撑稳定。', 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80', 1),
(2, 'Adidas Ultraboost 23 跑步鞋', '运动鞋', 1199.00, 80, 'Adidas 旗舰级缓震跑鞋，Light BOOST 科技带来轻盈脚感与高能回弹，Primeknit 贴合包裹。', 'https://images.unsplash.com/photo-1584735935682-2f2b69dff9d2?auto=format&fit=crop&w=600&q=80', 1),
(3, '李宁赤兔6 Pro 专业竞速跑步鞋', '运动鞋', 499.00, 150, '国产高性价比专业竞速训练鞋，全掌䨻科技中底，滚动感强，透气耐磨，适合进阶跑者日常拉练。', 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80', 1),
(4, '苹果 iPhone 15 Pro 128GB 原色钛金属', '手机', 7999.00, 50, 'A17 Pro 芯片，航空级钛金属边框，支持光线追踪游戏，4800 万像素主摄三摄系统，USB-C 高速接口。', 'https://images.unsplash.com/photo-1695048133142-1a20484d2569?auto=format&fit=crop&w=600&q=80', 1),
(5, '小米 14 256GB 黑色 (徕卡光学)', '手机', 3999.00, 80, '小尺寸全能旗舰，高通骁龙 8 Gen 3 处理器，新一代徕卡 Summilux 镜头，1.5K 超窄边直屏，IP68 级防尘防水。', 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=600&q=80', 1),
(6, '华为 Mate 60 Pro 256GB 雅丹黑', '手机', 6999.00, 60, '麒麟 9000S 芯片，超可靠玄武架构，全球首发卫星通话，超微距长焦镜头，鸿蒙 HarmonyOS 4 系统。', 'https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=600&q=80', 1);

-- 订单库初始化
CREATE DATABASE IF NOT EXISTS aimall_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aimall_order;
CREATE TABLE IF NOT EXISTS orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    order_no     VARCHAR(50) NOT NULL UNIQUE COMMENT '订单号',
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    product_id   BIGINT NOT NULL COMMENT '商品ID',
    product_name VARCHAR(200) NOT NULL COMMENT '商品名（冗余）',
    price        DECIMAL(10,2) NOT NULL COMMENT '单价',
    quantity     INT NOT NULL COMMENT '数量',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '总金额',
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/PAID/SHIPPED/DONE/CANCELLED',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- 支付流水表初始化
CREATE TABLE IF NOT EXISTS payment_records (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '支付ID',
    pay_sn       VARCHAR(64) NOT NULL UNIQUE COMMENT '支付流水号',
    order_no     VARCHAR(64) NOT NULL COMMENT '订单号',
    user_id      BIGINT NOT NULL COMMENT '用户ID',
    amount       DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    pay_channel  VARCHAR(32) NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道 ALIPAY/WECHAT/MOCK',
    trade_no     VARCHAR(64) COMMENT '第三方交易流水号',
    status       VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT '状态 INIT/SUCCESS/FAILED/REFUNDED',
    paid_at      DATETIME COMMENT '支付完成时间',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT NOT NULL DEFAULT 0,
    INDEX idx_order_no (order_no),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水记录表';
