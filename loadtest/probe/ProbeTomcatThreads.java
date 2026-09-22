import org.springframework.boot.autoconfigure.web.ServerProperties;

/**
 * 探针：读出「应用未配置时 Tomcat 连接器的默认值」，把压测报告里的
 * 「线程上限 = 200」从"文档说默认是 200"变成「从构建实际使用的 jar 里读出来的」。
 *
 * 为什么需要它：本仓库既没有配置 server.tomcat.*，指标栈也没暴露 Tomcat 线程指标
 * （只有 tomcat_sessions_*），configprops/env 端点同样未开放
 * ⇒ 运行期根本读不到这个值，只能从类默认值来定。
 *
 * 跑法（从工作树根执行）。三个 jar 必须与构建实际使用的版本一致——从产物里读：
 *   jar tf target/exam-online.jar | grep spring-core     → BOOT-INF/lib/spring-core-6.2.10.jar
 * spring-core 不可省：ServerProperties 的构造函数会用到 org.springframework.util.unit.DataSize，
 * 少了它会以 NoClassDefFoundError 失败，看起来像"类不存在"。也**不要**用 glob 去猜版本——
 * 本机 .m2-repo 里同时躺着 spring-core 的 5.2/5.3/6.0/6.1/6.2/7.0 六代，随便抓一个就错。
 * -encoding 必须显式 UTF-8（本机 javac 默认 GBK）；classpath 分隔符在 Windows 下是 `;`。
 *
 *   R="D:/code/examOnline/.m2-repo"
 *   AC="$R/org/springframework/boot/spring-boot-autoconfigure/3.5.5/spring-boot-autoconfigure-3.5.5.jar"
 *   SB="$R/org/springframework/boot/spring-boot/3.5.5/spring-boot-3.5.5.jar"
 *   SC="$R/org/springframework/spring-core/6.2.10/spring-core-6.2.10.jar"
 *   'D:\develop\jdk177\bin\javac.exe' -encoding UTF-8 -cp "$AC;$SB;$SC" -d target/probe loadtest/probe/ProbeTomcatThreads.java
 *   'D:\develop\jdk177\bin\java.exe' -cp "target/probe;$AC;$SB;$SC" ProbeTomcatThreads
 *
 * 实测输出（2026-09-22，spring-boot-autoconfigure 3.5.5 + spring-core 6.2.10）：
 *   threads.max = 200，min-spare = 10，accept-count = 100，max-connections = 8192
 */
public class ProbeTomcatThreads {
    public static void main(String[] args) {
        ServerProperties p = new ServerProperties();
        ServerProperties.Tomcat t = p.getTomcat();
        System.out.println("== 来源：spring-boot-autoconfigure 的 ServerProperties 默认值 ==");
        System.out.println("server.tomcat.threads.max       = " + t.getThreads().getMax());
        System.out.println("server.tomcat.threads.min-spare = " + t.getThreads().getMinSpare());
        System.out.println("server.tomcat.accept-count      = " + t.getAcceptCount());
        System.out.println("server.tomcat.max-connections   = " + t.getMaxConnections());
        System.out.println("== application*.yml 零 tomcat 配置 ⇒ 运行时即取上述值 ==");
    }
}
