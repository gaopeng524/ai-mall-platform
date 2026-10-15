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
(1, 'Nike Air Zoom Pegasus 40 跑步鞋 (经典黑白)', '运动鞋', 799.00, 100, 'Nike 经典跑步鞋，适合日常跑步与长距离训练，React 泡棉结合双 Zoom Air 气垫，轻量透气，支撑稳定。黑白经典配色。', 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80', 1),
(2, 'Adidas Ultraboost 23 顶级缓震跑鞋', '运动鞋', 1199.00, 80, 'Adidas 旗舰级缓震跑鞋，Light BOOST 科技带来轻盈脚感与高能回弹，Primeknit 贴合包裹，马牌大底防滑耐磨。', 'https://images.unsplash.com/photo-1584735935682-2f2b69dff9d2?auto=format&fit=crop&w=600&q=80', 1),
(3, '李宁赤兔6 Pro 专业竞速跑步鞋 (性价比平替)', '运动鞋', 499.00, 150, '国产高性价比专业竞速训练鞋，全掌䨻科技中底，滚动感强，透气耐磨，适合进阶跑者日常拉练，千元内跑鞋平替首选。', 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80', 1),
(4, 'Nike Air Force 1 07 空军一号低帮经典板鞋', '运动鞋', 749.00, 120, '耐克传奇经典街头板鞋，纯白皮革鞋面，内置 Air-Sole 气垫缓震，日常百搭潮流单品。', 'https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a?auto=format&fit=crop&w=600&q=80', 1),
(5, '回力经典复古低帮小白鞋 (国民百元平替)', '运动鞋', 79.00, 300, '国民复古经典百搭帆布板鞋，橡胶硫化大底防滑耐磨，透气舒适帆布鞋面，学生党与日常百元内板鞋极致平替。', 'https://images.unsplash.com/photo-1525966222134-fcfa99b8ae77?auto=format&fit=crop&w=600&q=80', 1),
(6, '苹果 iPhone 15 Pro 128GB 原色钛金属', '手机', 7999.00, 50, 'A17 Pro 3nm 芯片，航空级钛金属机身，支持硬件级光线追踪，4800 万像素三摄系统，USB-C 3.0 高速接口。', 'https://images.unsplash.com/photo-1695048133142-1a20484d2569?auto=format&fit=crop&w=600&q=80', 1),
(7, '华为 Mate 60 Pro 256GB 雅丹黑', '手机', 6999.00, 60, '麒麟 9000S 芯片，超可靠玄武架构，天通卫星双向通话，超微距长焦镜头，鸿蒙 HarmonyOS 4 智慧生态。', 'https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=600&q=80', 1),
(8, '小米 14 256GB 黑色 (徕卡光学影像旗舰)', '手机', 3999.00, 80, '小尺寸全能旗舰，高通骁龙 8 Gen 3 处理器，新一代徕卡 Summilux 镜头，1.5K 超窄边直屏，IP68 防尘防水。', 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=600&q=80', 1),
(9, '红米 Redmi K70 16GB+256GB 墨羽 (性能平替)', '手机', 2399.00, 100, '第二代骁龙 8 旗舰芯，2K 第二代中国屏，120W 快充 + 5000mAh 大电池，光影猎人 800 影像，两千档性能平替神机。', 'https://images.unsplash.com/photo-1580910051074-3eb694886505?auto=format&fit=crop&w=600&q=80', 1),
(10, '索尼 WH-1000XM5 头戴式无线降噪耳机 (黑色)', '耳机', 2999.00, 50, '索尼旗舰双芯降噪系统，8 麦克风主动降噪，30 小时长续航，LDAC 高解析音频传输，头戴式降噪天花板。', 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=600&q=80', 1),
(11, '漫步者 W820NB 双金标头戴式无线降噪耳机 (平替款)', '耳机', 299.00, 200, 'Hi-Res 双金标认证，-43dB 深度混合主动降噪，零压感专利耳套，50小时超长续航，三百元头戴降噪神级平替。', 'https://images.unsplash.com/photo-1546435770-a3e426bf472b?auto=format&fit=crop&w=600&q=80', 1),
(12, '苹果 AirPods Pro 2 主动降噪无线入耳耳机', '耳机', 1899.00, 70, 'H2 芯片驱动，自适应音频模式，主动降噪能力翻倍，个性化空间音频支持动态头部追踪，MagSafe 充电盒。', 'https://images.unsplash.com/photo-1600294037681-c80b4cb5b434?auto=format&fit=crop&w=600&q=80', 1),
(13, '红米 Redmi Buds 5 Pro 主动降噪蓝牙耳机 (平替款)', '耳机', 399.00, 160, '52dB 深度主动降噪，圈瓷同轴双单元高解析音质，LHDC 5.0 超清传输协议，苹果 AirPods Pro 百元级平替。', 'https://images.unsplash.com/photo-1590658268037-6bf12165a8df?auto=format&fit=crop&w=600&q=80', 1),
(14, '罗技 G PRO X SUPERLIGHT 2 (GPW二代狗屁王)', '数码配件', 1099.00, 60, '60克极致轻量化设计，HERO 2 传感器 32000 DPI，LIGHTFORCE 光学机械混合微动，职业电竞选手首选鼠标。', 'https://images.unsplash.com/photo-1615663245857-ac93bb7c39e7?auto=format&fit=crop&w=600&q=80', 1),
(15, '雷柏 VT9 PRO 双模轻量化游戏鼠标 (狗屁王平替)', '数码配件', 199.00, 180, '原相 PAW3395 顶级电竞传感器，68克轻量化对称手感，C+click 分离式按键预压校准，两百元内电竞鼠皇平替。', 'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?auto=format&fit=crop&w=600&q=80', 1),
(16, 'Keychron Q1 Pro 全铝客制化无线机械键盘', '数码配件', 1188.00, 40, 'CNC 全铝合金机身，Gasket 软弹减震结构，支持 QMK/VIA 开源改键，三模无线热插拔，程序员客制化退烧神器。', 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=600&q=80', 1),
(17, '达尔优 A98 三模热插拔机械键盘 (客制化平替)', '数码配件', 399.00, 120, '创新 Gasket 结构消音垫，天空轴 V3 顺滑线性轴体，98 配列带独立小键盘，RGB 炫彩背光，三模无线连接平替。', 'https://images.unsplash.com/photo-1618384887929-16ec33fab9ef?auto=format&fit=crop&w=600&q=80', 1),
(18, 'The North Face 北面 1996 经典复古 700蓬羽绒服', '服饰', 2998.00, 40, '北面经典 1996 标志性版型，RDS 认证 700 蓬松度高品质鹅绒，DWR 防泼水防撕裂科技面料，冬季保暖潮流标杆。', 'https://images.unsplash.com/photo-1544923246-77307dd654cb?auto=format&fit=crop&w=600&q=80', 1),
(19, '波司登极寒系列 鹅绒加厚保暖羽绒服 (抗寒平替)', '服饰', 1299.00, 80, '三重保暖黑科技，600+ 蓬松度高品质大朵白鹅绒，防风防水透气科技面料，抵御零下 30 度严寒，高品质羽绒平替。', 'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?auto=format&fit=crop&w=600&q=80', 1),
(20, '始祖鸟 Arc\'teryx Beta AR 男士户外硬壳冲锋衣', '服饰', 6000.00, 20, 'GORE-TEX PRO 极致防暴雨面料，全压胶防水拉链，腋下透气设计，高山攀登与极地探险专业顶级硬壳。', 'https://images.unsplash.com/photo-1606902965551-dce093cda6e7?auto=format&fit=crop&w=600&q=80', 1),
(21, '拓路者雪峰 三合一暴雨级户外冲锋衣 (冲锋衣平替)', '服饰', 499.00, 150, '暴雨级防水透湿科技面料，可拆卸加厚摇粒绒内胆，一衣三穿防风御寒，徒步露营高性价比户外硬壳平替。', 'https://images.unsplash.com/photo-1551028719-00167b16eac5?auto=format&fit=crop&w=600&q=80', 1),
(22, '德龙 De\'Longhi 经典半自动意式泵压咖啡机', '生活电器', 2180.00, 35, '15Bar 黄金萃取压力，专业温控系统与手动蒸汽打奶泡系统，Crema 醇厚油脂，打造家庭高品质意式浓缩咖啡角。', 'https://images.unsplash.com/photo-1517668808822-9ebb02f2a0e6?auto=format&fit=crop&w=600&q=80', 1),
(23, '柏翠 PE3690 意式半自动研磨一体咖啡机 (家用平替)', '生活电器', 699.00, 90, '20Bar 高压萃取，智能温控精准萃取，强劲蒸汽打发细腻奶泡，紧凑不占地，千元内入门级意式咖啡机超值平替。', 'https://images.unsplash.com/photo-1509042239860-f550ce710b93?auto=format&fit=crop&w=600&q=80', 1),
(24, '小米经典商务电脑双肩包 (通勤平替款)', '箱包配饰', 99.00, 260, '四级防泼水面料，独立 15.6 英寸防震笔记本电脑仓，S型加宽贴合减压肩带，99元高品质都市通勤双肩包平替。', 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=600&q=80', 1);

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
