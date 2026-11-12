package com.aimall.order.it;

import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;

/**
 * 秒杀故障注入测试的基础设施（MySQL + Redis + RocketMQ），整个测试 JVM 共享一份。
 *
 * <ul>
 *   <li>有 Docker：Testcontainers 启动 mysql:8.0、redis:7-alpine、apache/rocketmq:5.2.0；</li>
 *   <li>没有 Docker：读取环境变量连接现成服务（例如本机安装的 MySQL/Redis/RocketMQ）：
 *       TEST_MYSQL_URL（如 jdbc:mysql://localhost:3306）/ TEST_MYSQL_USER / TEST_MYSQL_PASSWORD，
 *       TEST_REDIS_HOST / TEST_REDIS_PORT，TEST_ROCKETMQ_NAMESRV（如 127.0.0.1:9876）；</li>
 *   <li>都没有：跳过（不是失败）。</li>
 * </ul>
 *
 * 为了不碰开发库，测试库名带 _it 后缀：用 docker/mysql/init.sql 重建 aimall_order_it / aimall_product_it。
 */
public final class SeckillTestInfra {

    public static final String ORDER_DB = "aimall_order_it";
    public static final String PRODUCT_DB = "aimall_product_it";

    private static SeckillTestInfra instance;

    public final String mysqlBaseUrl;
    public final String mysqlUser;
    public final String mysqlPassword;
    public final String redisHost;
    public final int redisPort;
    public final String rocketmqNamesrv;

    private SeckillTestInfra(String mysqlBaseUrl, String mysqlUser, String mysqlPassword,
                             String redisHost, int redisPort, String rocketmqNamesrv) {
        this.mysqlBaseUrl = mysqlBaseUrl;
        this.mysqlUser = mysqlUser;
        this.mysqlPassword = mysqlPassword;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.rocketmqNamesrv = rocketmqNamesrv;
    }

    /** @param needRocketMq 是否需要 RocketMQ（扣库 worker 测试只需要 MySQL） */
    public static synchronized SeckillTestInfra get(boolean needRocketMq) {
        if (instance == null) {
            instance = start();
        }
        if (needRocketMq) {
            Assumptions.assumeTrue(instance.rocketmqNamesrv != null, "没有可用的 RocketMQ，跳过");
        }
        return instance;
    }

    private static SeckillTestInfra start() {
        String envMysql = System.getenv("TEST_MYSQL_URL");
        SeckillTestInfra infra;
        if (envMysql != null && !envMysql.isBlank()) {
            infra = new SeckillTestInfra(envMysql,
                    System.getenv().getOrDefault("TEST_MYSQL_USER", "root"),
                    System.getenv().getOrDefault("TEST_MYSQL_PASSWORD", ""),
                    System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379")),
                    System.getenv("TEST_ROCKETMQ_NAMESRV"));
        } else {
            boolean docker;
            try {
                docker = DockerClientFactory.instance().isDockerAvailable();
            } catch (Throwable t) {
                docker = false;
            }
            Assumptions.assumeTrue(docker, "没有 Docker，也没有设置 TEST_MYSQL_URL，跳过秒杀集成测试");
            infra = startContainers();
        }
        infra.recreateSchemas();
        return infra;
    }

    @SuppressWarnings("resource")
    private static SeckillTestInfra startContainers() {
        MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withUsername("root").withPassword("test").withDatabaseName("mysql")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        mysql.start();

        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();

        // RocketMQ：namesrv + broker 跑在同一个容器里。broker 会把 brokerIP1:10911 注册到 namesrv，
        // 客户端直接按这个地址连 broker，所以这里用固定端口映射 + brokerIP1=127.0.0.1。
        GenericContainer<?> rocketmq = new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.2.0"))
                .withCopyToContainer(MountableFile.forClasspathResource("rocketmq/broker.conf"), "/home/rocketmq/broker.conf")
                .withCommand("sh", "-c", "./mqnamesrv & sleep 3 && ./mqbroker -n 127.0.0.1:9876 -c /home/rocketmq/broker.conf")
                .withWorkingDirectory("/home/rocketmq/rocketmq-5.2.0/bin")
                .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        com.github.dockerjava.api.model.PortBinding.parse("9876:9876"),
                        com.github.dockerjava.api.model.PortBinding.parse("10911:10911"),
                        com.github.dockerjava.api.model.PortBinding.parse("10909:10909")))
                .withExposedPorts(9876, 10911)
                .waitingFor(Wait.forLogMessage(".*boot success.*broker.*\\n", 1).withStartupTimeout(Duration.ofMinutes(2)));
        rocketmq.start();

        String base = "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306);
        return new SeckillTestInfra(base, "root", "test",
                redis.getHost(), redis.getMappedPort(6379), "127.0.0.1:9876");
    }

    public String jdbcUrl(String db) {
        return mysqlBaseUrl + "/" + db + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";
    }

    public DataSource dataSource(String db) {
        DriverManagerDataSource ds = new DriverManagerDataSource(jdbcUrl(db), mysqlUser, mysqlPassword);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    /** 用仓库里的 docker/mysql/init.sql 重建 *_it 测试库（库名替换为 _it 后缀） */
    private void recreateSchemas() {
        try {
            Path init = locateInitSql();
            String sql = Files.readString(init, StandardCharsets.UTF_8)
                    .replace("aimall_order", ORDER_DB)
                    .replace("aimall_product", PRODUCT_DB)
                    .replace("aimall_user", "aimall_user_it");
            Path tmp = Files.createTempFile("init-it", ".sql");
            Files.writeString(tmp, "DROP DATABASE IF EXISTS " + ORDER_DB + ";\nDROP DATABASE IF EXISTS " + PRODUCT_DB
                    + ";\nDROP DATABASE IF EXISTS aimall_user_it;\n" + sql, StandardCharsets.UTF_8);
            try (Connection c = dataSource("mysql").getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource(tmp));
            }
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            throw new IllegalStateException("初始化测试库失败: " + e.getMessage(), e);
        }
    }

    private static Path locateInitSql() {
        for (Path p : new Path[]{Path.of("../docker/mysql/init.sql"), Path.of("docker/mysql/init.sql")}) {
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("找不到 docker/mysql/init.sql");
    }
}
