# YKB Swagger Generator

Bu proje, IntelliJ IDEA icin gelistirilmis bir JetBrains eklentisidir. Eklenti,
Swagger/OpenAPI JSON dokumanlarini okuyarak secilen endpoint'ler icin Java
tarafinda DTO, servis imzasi, mapper metodu ve SQL kayit scripti uretir.

## Ne Ise Yarar?

Eklenti IntelliJ IDEA icinde `Tools > Swagger Plugin...` menusunden calisir.
Kullanici bir Swagger/OpenAPI URL'si girer veya yerel bir `.json` dosyasi secer.
Dokumandaki endpoint'ler tag'lere gore listelenir ve kullanici uretim yapmak
istedigi endpoint'leri secer.

Secilen endpoint'ler icin:

- Remote DTO Java class'lari uretilir.
- Backend DTO Java class'lari uretilir.
- Remote ve backend servis interface'lerine method imzalari eklenir.
- Mapper class icine DTO donusum method'lari eklenir.
- `NGI_REST_REMOTE_REGISTRIES` tablosu icin DELETE/INSERT SQL scripti uretilir.
- Uretilen SQL scripti IntelliJ icinde scratch dosya olarak acilir.

Proje YKB'ye ozel Java backend yapisina gore tasarlanmistir. DTO uretiminde
`BaseRestDTO`, `BaseRequest` ve `BaseResponse` gibi kurum ici base class'lar
kullanilir.

## Kullanilan Teknolojiler

- Kotlin
- Gradle Kotlin DSL
- Java 11 bytecode compatibility for IntelliJ IDEA 2022.1 / build 221
- IntelliJ Platform Plugin SDK
- IntelliJ PSI API
- Swing ve IntelliJ UI bilesenleri
- Jackson `ObjectMapper`

## Ana Akis

1. Kullanici Swagger/OpenAPI URL'si veya JSON dosyasi secer.
2. Eklenti JSON dokumanini parse eder.
3. Endpoint'ler secilebilir bir agac yapisinda gosterilir.
4. Kullanici hedef DTO klasorlerini, servis interface dosyalarini ve mapper
   class dosyasini secer.
5. Eklenti secilen endpoint'lere gore Java dosyalarini olusturur veya gunceller.
6. REST remote registry icin SQL script uretip editor icinde acar.

## Proje Yapisi

- `src/main/kotlin/org/example/ykplugin/action`: IntelliJ action giris noktasi.
- `src/main/kotlin/org/example/ykplugin/ui`: Kullanici arayuzu ve generator dialog'u.
- `src/main/kotlin/org/example/ykplugin/parser`: Swagger/OpenAPI JSON parser.
- `src/main/kotlin/org/example/ykplugin/generator`: DTO, servis, mapper ve SQL uretimi.
- `src/main/kotlin/org/example/ykplugin/model`: OpenAPI model siniflari.
- `src/main/resources/META-INF/plugin.xml`: IntelliJ plugin tanimi.

## Notlar

- Yalnizca JSON formatindaki Swagger/OpenAPI dokumanlari desteklenir.
- YAML destegi bulunmamaktadir.
- Eklenti Java PSI API kullandigi icin IntelliJ Java destegine bagimlidir.
