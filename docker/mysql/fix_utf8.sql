SET NAMES utf8mb4;
TRUNCATE TABLE aimall_product.products;
INSERT INTO aimall_product.products (id, name, category, price, stock, description) VALUES
(1, 'Nike Air Zoom Pegasus 40 跑步鞋', '运动鞋', 799.00, 100, 'Nike经典跑步鞋，适合日常跑步训练，轻量透气，支撑性好'),
(2, 'Adidas Ultraboost 23 跑步鞋', '运动鞋', 1199.00, 80, 'Adidas旗舰跑步鞋，Boost科技缓震，适合长距离跑步'),
(3, '李宁赤兔6 Pro 跑步鞋', '运动鞋', 499.00, 150, '国产专业竞速跑鞋，碳板科技，适合比赛和进阶跑者'),
(4, '苹果 iPhone 15 Pro 128GB', '手机', 7999.00, 50, 'A17 Pro芯片，钛金属机身，4800万像素三摄系统'),
(5, '小米 14 256GB', '手机', 3999.00, 80, '骁龙8 Gen3，徕卡影像，IP68防水'),
(6, '华为Mate60 Pro 256GB', '手机', 6999.00, 60, '麒麟9000S芯片，超清卫星通话，万亿参数AI助手');
