# 后端运行镜像。JAR 已在构建机用 maven 打好，这里只 COPY，构建不需要联网。
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/offline-demo-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]