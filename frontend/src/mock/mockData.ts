import { Product, Order } from '../types';

export const MOCK_PRODUCTS: Product[] = [
  // 1. 运动鞋
  {
    id: 1,
    name: 'Nike Air Zoom Pegasus 40 跑步鞋 (经典黑白)',
    category: '运动鞋',
    price: 799.00,
    stock: 100,
    description: 'Nike 经典跑步鞋，适合日常跑步与长距离训练，React 泡棉结合双 Zoom Air 气垫，轻量透气，支撑稳定。黑白经典配色。',
    imageUrl: 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=600&q=80',
    tags: ['AI热推', '爆款经典', '透气减震'],
    specs: {
      '适用场景': '日常慢跑 / 5-10公里训练',
      '中底科技': 'React + 前后掌 Zoom Air',
      '重量': '约 285g (42码)',
      '鞋面材质': '工程网眼布'
    }
  },
  {
    id: 2,
    name: 'Adidas Ultraboost 23 顶级缓震跑鞋',
    category: '运动鞋',
    price: 1199.00,
    stock: 80,
    description: 'Adidas 旗舰级缓震跑鞋，Light BOOST 科技带来轻盈脚感与高能回弹，Primeknit 贴合包裹，马牌大底防滑耐磨。',
    imageUrl: 'https://images.unsplash.com/photo-1584735935682-2f2b69dff9d2?auto=format&fit=crop&w=600&q=80',
    tags: ['旗舰缓震', '长跑之选'],
    specs: {
      '适用场景': '半马/全马 / 日常通勤',
      '中底科技': 'Light BOOST 高能回弹',
      '重量': '约 295g',
      '大底科技': 'Continental 马牌橡胶'
    }
  },
  {
    id: 3,
    name: '李宁赤兔6 Pro 专业竞速跑步鞋 (性价比平替)',
    category: '运动鞋',
    price: 499.00,
    stock: 150,
    description: '国产高性价比专业竞速训练鞋，全掌䨻科技中底，滚动感强，透气耐磨，适合进阶跑者日常拉练，千元内跑鞋平替首选。',
    imageUrl: 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80',
    tags: ['千元内首选', '轻量回弹', '平替标杆'],
    specs: {
      '适用场景': '竞速训练 / 跑道拉练',
      '中底科技': '全掌李宁䨻科技',
      '重量': '约 210g (超轻)',
      '抗扭支撑': 'PROBAR LOC'
    }
  },
  {
    id: 4,
    name: 'Nike Air Force 1 07 空军一号低帮经典板鞋',
    category: '运动鞋',
    price: 749.00,
    stock: 120,
    description: '耐克传奇经典街头板鞋，纯白皮革鞋面，内置 Air-Sole 气垫缓震，日常百搭潮流单品。',
    imageUrl: 'https://images.unsplash.com/photo-1595950653106-6c9ebd614d3a?auto=format&fit=crop&w=600&q=80',
    tags: ['街头经典', '纯白百搭', '空军一号'],
    specs: {
      '鞋帮高度': '低帮',
      '鞋底材质': '耐磨橡胶 + Air-Sole',
      '鞋面材质': '头层牛皮革'
    }
  },
  {
    id: 5,
    name: '回力经典复古低帮小白鞋 (国民百元平替)',
    category: '运动鞋',
    price: 79.00,
    stock: 300,
    description: '国民复古经典百搭帆布板鞋，橡胶硫化大底防滑耐磨，透气舒适帆布鞋面，学生党与日常百元内板鞋极致平替。',
    imageUrl: 'https://images.unsplash.com/photo-1525966222134-fcfa99b8ae77?auto=format&fit=crop&w=600&q=80',
    tags: ['国民平替', '百元内必入', '复古怀旧'],
    specs: {
      '鞋面材质': '透气帆布',
      '鞋底工艺': '硫化橡胶底',
      '适用人群': '青年学生 / 休闲通勤'
    }
  },

  // 2. 手机数码
  {
    id: 6,
    name: '苹果 iPhone 15 Pro 128GB 原色钛金属',
    category: '手机',
    price: 7999.00,
    stock: 50,
    description: 'A17 Pro 3nm 芯片，航空级钛金属机身，支持硬件级光线追踪，4800 万像素三摄系统，USB-C 3.0 高速接口。',
    imageUrl: 'https://images.unsplash.com/photo-1695048133142-1a20484d2569?auto=format&fit=crop&w=600&q=80',
    tags: ['A17 Pro', '钛金属', '4800万三摄'],
    specs: {
      '处理器': 'Apple A17 Pro (3nm制程)',
      '屏幕': '6.1 英寸 Super Retina XDR 120Hz',
      '电池/充电': '支持 MagSafe 15W 无线充',
      '接口': 'USB-C 3.0'
    }
  },
  {
    id: 7,
    name: '华为 Mate 60 Pro 256GB 雅丹黑',
    category: '手机',
    price: 6999.00,
    stock: 60,
    description: '麒麟 9000S 芯片，超可靠玄武架构，天通卫星双向通话，超微距长焦镜头，鸿蒙 HarmonyOS 4 智慧生态。',
    imageUrl: 'https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=600&q=80',
    tags: ['卫星通话', '玄武架构', '鸿蒙OS'],
    specs: {
      '处理器': '麒麟 9000S',
      '通信能力': '支持天通卫星双向通话',
      '防护架构': '超可靠玄武架构 + 第二代昆仑玻璃',
      '充电': '88W 有线超级快充 + 50W 无线'
    }
  },
  {
    id: 8,
    name: '小米 14 256GB 黑色 (徕卡光学影像旗舰)',
    category: '手机',
    price: 3999.00,
    stock: 80,
    description: '小尺寸全能旗舰，高通骁龙 8 Gen 3 处理器，新一代徕卡 Summilux 镜头，1.5K 超窄边直屏，IP68 防尘防水。',
    imageUrl: 'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=600&q=80',
    tags: ['骁龙8 Gen3', '徕卡影像', '小屏旗舰'],
    specs: {
      '处理器': '骁龙 8 Gen3',
      '影像系统': '徕卡 Summilux 75mm 浮动长焦',
      '充电续航': '4610mAh + 90W 闪充 + 50W 无线',
      '防水防尘': 'IP68'
    }
  },
  {
    id: 9,
    name: '红米 Redmi K70 16GB+256GB 墨羽 (性能平替)',
    category: '手机',
    price: 2399.00,
    stock: 100,
    description: '第二代骁龙 8 旗舰芯，2K 第二代中国屏，120W 快充 + 5000mAh 大电池，光影猎人 800 影像，两千档性能平替神机。',
    imageUrl: 'https://images.unsplash.com/photo-1580910051074-3eb694886505?auto=format&fit=crop&w=600&q=80',
    tags: ['性能水桶机', '2K顶级直屏', '2000档平替'],
    specs: {
      '处理器': '第二代骁龙 8 移动平台',
      '屏幕素质': '2K 华星 C8 发光材质 120Hz',
      '快充规格': '120W 神仙秒充 + 5000mAh'
    }
  },

  // 3. 耳机影音
  {
    id: 10,
    name: '索尼 WH-1000XM5 头戴式无线降噪耳机 (黑色)',
    category: '耳机',
    price: 2999.00,
    stock: 50,
    description: '索尼旗舰双芯降噪系统，8 麦克风主动降噪，30 小时长续航，LDAC 高解析音频传输，头戴式降噪天花板。',
    imageUrl: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=600&q=80',
    tags: ['双芯旗舰降噪', 'LDAC无损', '头戴机皇'],
    specs: {
      '降噪技术': 'QN1 + V1 双芯片 8 麦克风主动降噪',
      '续航时间': '30小时 (开启降噪)',
      '声学单元': '30mm 碳纤维振膜'
    }
  },
  {
    id: 11,
    name: '漫步者 W820NB 双金标头戴式无线降噪耳机 (平替款)',
    category: '耳机',
    price: 299.00,
    stock: 200,
    description: 'Hi-Res 双金标认证，-43dB 深度混合主动降噪，零压感专利耳套，50小时超长续航，三百元头戴降噪神级平替。',
    imageUrl: 'https://images.unsplash.com/photo-1546435770-a3e426bf472b?auto=format&fit=crop&w=600&q=80',
    tags: ['双金标认证', '300元平替神机', '超长50h续航'],
    specs: {
      '降噪深度': '-43dB 混合主动降噪',
      '续航能力': '50小时超长续航 (降噪关)',
      '认证标准': 'Hi-Res Audio 双金标'
    }
  },
  {
    id: 12,
    name: '苹果 AirPods Pro 2 主动降噪无线入耳耳机',
    category: '耳机',
    price: 1899.00,
    stock: 70,
    description: 'H2 芯片驱动，自适应音频模式，主动降噪能力翻倍，个性化空间音频支持动态头部追踪，MagSafe 充电盒。',
    imageUrl: 'https://images.unsplash.com/photo-1600294037681-c80b4cb5b434?auto=format&fit=crop&w=600&q=80',
    tags: ['苹果生态', 'H2芯片', '自适应通透'],
    specs: {
      '芯片': 'Apple H2 芯片',
      '特色功能': '空间音频 + 自适应音频 + 对话感知',
      '充电盒': 'MagSafe 充电盒 (USB-C 扬声器及挂绳孔)'
    }
  },
  {
    id: 13,
    name: '红米 Redmi Buds 5 Pro 主动降噪蓝牙耳机 (平替款)',
    category: '耳机',
    price: 399.00,
    stock: 160,
    description: '52dB 深度主动降噪，圈瓷同轴双单元高解析音质，LHDC 5.0 超清传输协议，苹果 AirPods Pro 百元级平替。',
    imageUrl: 'https://images.unsplash.com/photo-1590658268037-6bf12165a8df?auto=format&fit=crop&w=600&q=80',
    tags: ['52dB降噪', '同轴双单元', 'AirPods平替'],
    specs: {
      '降噪深度': '52dB / 4kHz 超宽频降噪',
      '发声单元': '10mm 压电陶瓷高音 + 11mm 镀钛动圈',
      '续航时间': '搭配充电盒 38 小时'
    }
  },

  // 4. 数码外设
  {
    id: 14,
    name: '罗技 G PRO X SUPERLIGHT 2 (GPW二代狗屁王)',
    category: '数码配件',
    price: 1099.00,
    stock: 60,
    description: '60克极致轻量化设计，HERO 2 传感器 32000 DPI，LIGHTFORCE 光学机械混合微动，职业电竞选手首选鼠标。',
    imageUrl: 'https://images.unsplash.com/photo-1615663245857-ac93bb7c39e7?auto=format&fit=crop&w=600&q=80',
    tags: ['电竞标杆', '60g超轻', 'HERO2传感器'],
    specs: {
      '重量': '约 60g 极致轻量',
      '传感器': 'HERO 2 (最高 32,000 DPI, 500+ IPS)',
      '轮询率': '无线 2000Hz'
    }
  },
  {
    id: 15,
    name: '雷柏 VT9 PRO 双模轻量化游戏鼠标 (狗屁王平替)',
    category: '数码配件',
    price: 199.00,
    stock: 180,
    description: '原相 PAW3395 顶级电竞传感器，68克轻量化对称手感，C+click 分离式按键预压校准，两百元内电竞鼠皇平替。',
    imageUrl: 'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?auto=format&fit=crop&w=600&q=80',
    tags: ['PAW3395神仙鼠', 'GPW平替', '两百元卷王'],
    specs: {
      '传感器': '原相 PAW3395 (26000 DPI)',
      '续航': '最高 160 小时',
      '重量': '约 68g'
    }
  },
  {
    id: 16,
    name: 'Keychron Q1 Pro 全铝客制化无线机械键盘',
    category: '数码配件',
    price: 1188.00,
    stock: 40,
    description: 'CNC 全铝合金机身，Gasket 软弹减震结构，支持 QMK/VIA 开源改键，三模无线热插拔，程序员客制化退烧神器。',
    imageUrl: 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=600&q=80',
    tags: ['客制化退烧', '全铝CNC', 'Gasket软弹'],
    specs: {
      '外壳工艺': '6063 全铝合金 CNC 精雕',
      '内部结构': 'Double-Gasket 双重软弹消音',
      '系统支持': 'Mac / Windows 双模无缝切换'
    }
  },
  {
    id: 17,
    name: '达尔优 A98 三模热插拔机械键盘 (客制化平替)',
    category: '数码配件',
    price: 399.00,
    stock: 120,
    description: '创新 Gasket 结构消音垫，天空轴 V3 顺滑线性轴体，98 配列带独立小键盘，RGB 炫彩背光，三模无线连接平替。',
    imageUrl: 'https://images.unsplash.com/photo-1618384887929-16ec33fab9ef?auto=format&fit=crop&w=600&q=80',
    tags: ['客制化平替', '天空轴V3', 'Gasket消音'],
    specs: {
      '轴体': '达尔优天空轴 V3 (线性手感)',
      '键位布局': '98 配列 (带数字小键盘)',
      '连接方式': 'Type-C / 2.4G / 蓝牙 5.1'
    }
  },

  // 5. 服饰户外
  {
    id: 18,
    name: 'The North Face 北面 1996 经典复古 700蓬羽绒服',
    category: '服饰',
    price: 2998.00,
    stock: 40,
    description: '北面经典 1996 标志性版型，RDS 认证 700 蓬松度高品质鹅绒，DWR 防泼水防撕裂科技面料，冬季保暖潮流标杆。',
    imageUrl: 'https://images.unsplash.com/photo-1544923246-77307dd654cb?auto=format&fit=crop&w=600&q=80',
    tags: ['冬季潮流', '700蓬高品质鹅绒', '经典1996'],
    specs: {
      '填充物': 'RDS 认证 700 蓬灰鹅绒 (充绒量约 280g)',
      '面料科技': 'Ripstop 40D 防撕裂锦纶 + DWR 防泼水',
      '版型风格': '经典美式 Oversize 复古面包服'
    }
  },
  {
    id: 19,
    name: '波司登极寒系列 鹅绒加厚保暖羽绒服 (抗寒平替)',
    category: '服饰',
    price: 1299.00,
    stock: 80,
    description: '三重保暖黑科技，600+ 蓬松度高品质大朵白鹅绒，防风防水透气科技面料，抵御零下 30 度严寒，高品质羽绒平替。',
    imageUrl: 'https://images.unsplash.com/photo-1539571696357-5a69c17a67c6?auto=format&fit=crop&w=600&q=80',
    tags: ['抵御-30℃严寒', '大朵白鹅绒', '高保暖平替'],
    specs: {
      '填充物': '90% 高品质大朵白鹅绒 (充绒量 260g+)',
      '御寒等级': '极寒防风暴 (-30°C 极寒适用)',
      '面料特性': '高密微孔透气防风暴雨面料'
    }
  },
  {
    id: 20,
    name: '始祖鸟 Arc\'teryx Beta AR 男士户外硬壳冲锋衣',
    category: '服饰',
    price: 6000.00,
    stock: 20,
    description: 'GORE-TEX PRO 极致防暴雨面料，全压胶防水拉链，腋下透气设计，高山攀登与极地探险专业顶级硬壳。',
    imageUrl: 'https://images.unsplash.com/photo-1606902965551-dce093cda6e7?auto=format&fit=crop&w=600&q=80',
    tags: ['户外天花板', 'GORE-TEX PRO', '顶级硬壳'],
    specs: {
      '科技面料': 'GORE-TEX PRO Most Rugged 三层复合',
      '防水透湿': '防水指数 28,000mm+ / 透湿 RET < 6',
      '适用场景': '高山攀登 / 极地穿越 / 暴雨徒步'
    }
  },
  {
    id: 21,
    name: '拓路者雪峰 三合一暴雨级户外冲锋衣 (冲锋衣平替)',
    category: '服饰',
    price: 499.00,
    stock: 150,
    description: '暴雨级防水透湿科技面料，可拆卸加厚摇粒绒内胆，一衣三穿防风御寒，徒步露营高性价比户外硬壳平替。',
    imageUrl: 'https://images.unsplash.com/photo-1551028719-00167b16eac5?auto=format&fit=crop&w=600&q=80',
    tags: ['户外平替标杆', '三合一拆卸内胆', '暴雨级防水'],
    specs: {
      '防水指数': '10,000mm 暴雨级防水',
      '内胆设计': '280g 加厚双面摇粒绒内胆 (可独立外穿)',
      '透气排湿': '8,000g/m²/24h 透湿率'
    }
  },

  // 6. 生活与出行
  {
    id: 22,
    name: '德龙 De\'Longhi 经典半自动意式泵压咖啡机',
    category: '生活电器',
    price: 2180.00,
    stock: 35,
    description: '15Bar 黄金萃取压力，专业温控系统与手动蒸汽打奶泡系统，Crema 醇厚油脂，打造家庭高品质意式浓缩咖啡角。',
    imageUrl: 'https://images.unsplash.com/photo-1517668808822-9ebb02f2a0e6?auto=format&fit=crop&w=600&q=80',
    tags: ['意式经典', '15Bar金萃', 'Crema醇厚油脂'],
    specs: {
      '萃取压力': '15Bar 意大利进口水泵',
      '打奶系统': '不锈钢高压蒸汽奶泡系统',
      '水箱容量': '1.1L 可拆卸透明水箱'
    }
  },
  {
    id: 23,
    name: '柏翠 PE3690 意式半自动研磨一体咖啡机 (家用平替)',
    category: '生活电器',
    price: 699.00,
    stock: 90,
    description: '20Bar 高压萃取，智能温控精准萃取，强劲蒸汽打发细腻奶泡，紧凑不占地，千元内入门级意式咖啡机超值平替。',
    imageUrl: 'https://images.unsplash.com/photo-1509042239860-f550ce710b93?auto=format&fit=crop&w=600&q=80',
    tags: ['千元内意式平替', '20Bar高压', '新手入门神器'],
    specs: {
      '泵浦压力': '20Bar 稳压萃取',
      '温控系统': 'PID 智能芯片精确控温',
      '特色设计': '双杯分流 + 顶部温杯区'
    }
  },
  {
    id: 24,
    name: '小米经典商务电脑双肩包 (通勤平替款)',
    category: '箱包配饰',
    price: 99.00,
    stock: 260,
    description: '四级防泼水面料，独立 15.6 英寸防震笔记本电脑仓，S型加宽贴合减压肩带，99元高品质都市通勤双肩包平替。',
    imageUrl: 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=600&q=80',
    tags: ['99元国民通勤', '4级防泼水', '15.6寸电脑防震仓'],
    specs: {
      '面料材质': '650D 强韧抗撕裂聚酯纤维 (4级防泼水)',
      '电脑仓位': '适配 15.6 英寸主流笔记本',
      '人体工学': '加厚蜂窝透气背板 + 减压S型肩带'
    }
  },

  // 秒杀专区
  {
    id: 25,
    name: '【🔥限时秒杀】索尼 WH-1000XM5 头戴式无线降噪耳机 (直降2000元)',
    category: '🔥 限时秒杀',
    price: 999.00,
    originalPrice: 2999.00,
    stock: 50,
    isSeckill: true,
    description: '【限时狂欢秒杀专享】旗舰级双芯降噪系统，30小时强劲续航，直通 10,000+ QPS 极速削峰秒杀通道！',
    imageUrl: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=600&q=80',
    tags: ['⚡ 限时秒杀', '直降2000', '10K QPS极速通道', '限量抢购'],
    specs: {
      '降噪技术': '双芯驱动 + 8麦克风主动降噪',
      '续航时间': '30小时 (开启降噪)',
      '快充能力': '充电3分钟播放3小时',
      '通道类型': 'RocketMQ 异步削峰 (10,271 QPS)'
    }
  },
  {
    id: 26,
    name: '【🔥限时秒杀】李宁赤兔6 Pro 竞速跑鞋 (限量特惠 ¥99)',
    category: '🔥 限时秒杀',
    price: 99.00,
    originalPrice: 499.00,
    stock: 100,
    isSeckill: true,
    description: '【限时2折秒杀】全掌䨻科技中底，轻量高回弹，单机 10,000+ QPS 极速异步抗峰测试标杆款！',
    imageUrl: 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80',
    tags: ['⚡ 限时秒杀', '1折狂欢', '超级Lua预扣', '手慢无'],
    specs: {
      '中底科技': '全掌李宁䨻科技',
      '秒杀限量': '100 双 (售完即止)',
      '抢购保护': '500ms 用户原子防抖 + 信号量背压',
      '通道类型': 'RocketMQ 异步削峰 (10,271 QPS)'
    }
  }
];

export const MOCK_ORDERS: Order[] = [
  {
    id: 101,
    orderNo: 'ORD202610080001',
    userId: 2,
    productId: 3,
    productName: '李宁赤兔6 Pro 专业竞速跑步鞋',
    price: 499.00,
    quantity: 1,
    totalAmount: 499.00,
    status: 'PAID',
    createdAt: '2026-10-08 14:20:10'
  },
  {
    id: 102,
    orderNo: 'ORD202610080002',
    userId: 2,
    productId: 1,
    productName: 'Nike Air Zoom Pegasus 40 跑步鞋',
    price: 799.00,
    quantity: 1,
    totalAmount: 799.00,
    status: 'PENDING',
    createdAt: '2026-10-08 15:05:32'
  }
];
