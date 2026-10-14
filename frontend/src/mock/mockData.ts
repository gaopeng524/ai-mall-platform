import { Product, Order } from '../types';

export const MOCK_PRODUCTS: Product[] = [
  {
    id: 1,
    name: 'Nike Air Zoom Pegasus 40 跑步鞋',
    category: '运动鞋',
    price: 799.00,
    stock: 100,
    description: 'Nike 经典跑步鞋，适合日常跑步与长距离训练，React 泡棉结合双 Zoom Air 气垫，轻量透气，支撑稳定。',
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
    name: 'Adidas Ultraboost 23 跑步鞋',
    category: '运动鞋',
    price: 1199.00,
    stock: 80,
    description: 'Adidas 旗舰级缓震跑鞋，Light BOOST 科技带来轻盈脚感与高能回弹，Primeknit 贴合包裹。',
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
    name: '李宁赤兔6 Pro 专业竞速跑步鞋',
    category: '运动鞋',
    price: 499.00,
    stock: 150,
    description: '国产高性价比专业竞速训练鞋，全掌䨻科技中底，滚动感强，透气耐磨，适合进阶跑者日常拉练。',
    imageUrl: 'https://images.unsplash.com/photo-1608231387042-66d1773070a5?auto=format&fit=crop&w=600&q=80',
    tags: ['千元内首选', '轻量回弹', '高性价比'],
    specs: {
      '适用场景': '竞速训练 / 跑道拉练',
      '中底科技': '全掌李宁䨻科技',
      '重量': '约 210g (超轻)',
      '抗扭支撑': 'PROBAR LOC'
    }
  },
  {
    id: 4,
    name: '苹果 iPhone 15 Pro 128GB 原色钛金属',
    category: '手机',
    price: 7999.00,
    stock: 50,
    description: 'A17 Pro 芯片，航空级钛金属边框，支持光线追踪游戏，4800 万像素主摄三摄系统，USB-C 高速接口。',
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
    id: 5,
    name: '小米 14 256GB 黑色 (徕卡光学)',
    category: '手机',
    price: 3999.00,
    stock: 80,
    description: '小尺寸全能旗舰，高通骁龙 8 Gen 3 处理器，新一代徕卡 Summilux 镜头，1.5K 超窄边直屏，IP68 级防尘防水。',
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
    id: 6,
    name: '华为 Mate 60 Pro 256GB 雅丹黑',
    category: '手机',
    price: 6999.00,
    stock: 60,
    description: '麒麟 9000S 芯片，超可靠玄武架构，全球首发卫星通话，超微距长焦镜头，鸿蒙 HarmonyOS 4 系统。',
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
    id: 7,
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
    id: 8,
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
