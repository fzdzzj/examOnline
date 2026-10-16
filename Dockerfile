# =============================================================================
# examOnline 后端多阶段构建镜像（add-deployment-stack）
#   docker build -t exam-online-app:prod .
#
# 阶段一（构建）：temurin 17 JDK + 仓库自足 wrapper（./mvnw 依赖 .mvn/ 全套）。
#   分层缓存：先 COPY pom.xml 拉依赖层，再 COPY 源码与 .mvn 走真正的 package。
#   不打包仓库内 .m2-repo（容器走公共仓库拉取，零机器绑定，见 .dockerignore）。
# 阶段二（运行）：temurin 17 JRE + target/exam-online.jar（finalName=exam-online，
#   见 pom.xml），JAVA_OPTS 作运行期调优挂载点（默认不调优——压测口径不动）。
#
# 生产 profile 的凭据全部经环境变量注入（DB_*/REDIS_*/SMTP_*/JWT_SECRET/
# ADMIN_*/RABBITMQ_*），缺变量即 fail-fast 启动失败，见 docker-compose.prod.yml。
# =============================================================================
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 复制构建所需全部：pom.xml、wrapper(mvnw+mvnw.cmd+.mvn)、maven-settings.xml、源码。
# 仓库本地 mvnw 是 CRLF（Windows 开发）行尾；容器内 sh/dash 读 CRLF 会语法错，先转 LF 并赋执行位。
# 注：不用 dependency:go-offline 预拉依赖层——该插件会试图下载 POM 里引用的可选/absent jar
#     （如 error_prone_annotations、checker-qual）导致构建失败；让 package 按需拉取即可
#     （与宿主门禁 `mvnw clean test` 成功路径一致，天然可用）。
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw mvnw.cmd ./
COPY maven-settings.xml ./
COPY src src
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# 真实 package（跳过测试——测试在仓库根 mvnw clean test 门禁里跑；此处产 runnable jar）
RUN sh ./mvnw -B -DskipTests package

# ---- 运行层 ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/exam-online.jar exam-online.jar

# TZ=Asia/Shanghai（fix-prod-container-tz）：基础镜像默认 UTC，而考试时间全按上海墙钟
# DATETIME 存储并以 LocalDateTime.now() 直接比较——不声明 TZ 则 prod 定时链路整体漂 8 小时。
# 基础镜像自带 tzdata，无需装包；build 层不声明（构建与时区无关）。
ENV TZ=Asia/Shanghai

# JAVA_OPTS：容器注入的 JVM 调优参数挂载点，默认空（不调优）
ENV JAVA_OPTS=""

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar exam-online.jar"]