plugins {
	id("jacoco")
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "br.andrew"
// Permite `./gradlew assemble -Pversion=1.2.3` no workflow de release.
version = if (project.hasProperty("version")) project.property("version") as String else "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Templates de relatorio. Handlebars e *logic-less*: nao avalia expressao
	// arbitraria, ao contrario do SpEL do Thymeleaf. Essa e a propriedade que
	// torna aceitavel receber template por upload, sem revisao humana.
	implementation("com.github.jknack:handlebars:4.4.0")

	// HTML -> PDF. LGPL, sem a clausula de rede da AGPL do iText.
	implementation("io.github.openhtmltopdf:openhtmltopdf-core:1.1.22")
	implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.22")

	// Sanitizacao do HTML enviado (allowlist de tags/atributos).
	implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")

	// Mesmo parser do sap-odbc: extrai as tabelas do SQL para conferir a allowlist.
	implementation("com.github.jsqlparser:jsqlparser:5.3")

	// YAML da definicao + JSON Schema que a IA usa como contrato.
	implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
	implementation("com.networknt:json-schema-validator:1.5.4")

	// JWT emitido pelo sap-rovema (mesma versao, para o formato bater).
	implementation("io.jsonwebtoken:jjwt-api:0.12.4")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.4")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.4")

	// Migracoes do schema SAP_REPORTS no HANA.
	// Boot 4 modularizou as autoconfiguracoes: `liquibase-core` sozinho NAO liga
	// o Liquibase no startup - falta o modulo que traz LiquibaseAutoConfiguration.
	implementation("org.springframework.boot:spring-boot-liquibase")
	implementation("org.liquibase:liquibase-core")
	runtimeOnly("com.sap.cloud.db.jdbc:ngdbc:2.29.11")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.h2database:h2")
	// Substitui o sap-odbc nos testes de integracao.
	testImplementation("org.wiremock:wiremock-standalone:3.9.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// application.yaml e local e ignorado pelo git. Quando ele NAO existe (CI,
// Docker, checkout limpo), o modelo versionado entra no lugar.
//
// A decisao e tomada em tempo de execucao da task, e o arquivo real e declarado
// como entrada: sem isso o Gradle reaproveitava do cache a saida de uma
// execucao antiga e a aplicacao subia com uma URL que nao existia mais em
// nenhum arquivo.
val configReal = file("src/main/resources/application.yaml")
val configModelo = file("src/main/resources/application.yaml.example")
// O modelo nao vai para o jar como ele mesmo. O exclude precisa ficar no
// sourceSet: dentro da task ele filtra TAMBEM o from() abaixo (o Gradle casa
// pelo nome de origem, antes do rename) e o fallback nao gerava arquivo nenhum.
sourceSets.main { resources.exclude("application.yaml.example") }
tasks.processResources {
	inputs.file(configModelo)
	inputs.files(configReal).optional()
	if (!configReal.exists()) {
		from(configModelo) { rename { "application.yaml" } }
	}
}

// `./gradlew bootRun` ativa o perfil `local` sozinho, lendo o application-local.yaml
// da raiz (ignorado pelo git). O arquivo versionado nunca precisa conter senha.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", "local")
}

tasks.named("jacocoTestReport", JacocoReport::class) {
	dependsOn(tasks.withType<Test>())
	group = "Reporting"
	reports {
		html.required.set(true)
		xml.required.set(true)
		csv.required.set(false)
		html.outputLocation.set(layout.buildDirectory.dir("jacocoHtml"))
		xml.outputLocation.set(layout.buildDirectory.file("jacoco.xml"))
	}
	classDirectories.from(
		files(classDirectories.files.map {
			fileTree(it) { exclude("**/*Test*.*") }
		})
	)
}
