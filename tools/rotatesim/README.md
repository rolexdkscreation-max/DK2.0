# Live connection बदलने का simulation (offline)

Gradle build इसे compile नहीं करता। इसमें असली `LiveSpare.kt` के नियम 30 मिनट की बातचीत पर 300 बार चलाए जाते हैं (एक बार पुराने तरीके से, एक बार नए से)।

चलाना: `kotlinc RotateSim.kt ../../app/src/main/java/com/dk/assistant/LiveSpare.kt -d rs.jar && java -cp rs.jar:kotlin-stdlib.jar com.dk.assistant.RotateSimKt` (तेज़ बातचीत के लिए `BUSY=1` लगाएँ)।

ये बातें अंदाज़े पर हैं, असली Gemini से नहीं ली गईं:
- connection लगभग 10 मिनट चलता है, goAway कटने से 5s पहले आता है
- जुड़ने में 400-1500ms लगते हैं, 5% कोशिशें नाकाम होती हैं
- नया handle जवाब पूरा होने के 300ms बाद आता है

नतीजे `RESULT_normal.txt` और `RESULT_busy.txt` में हैं।
