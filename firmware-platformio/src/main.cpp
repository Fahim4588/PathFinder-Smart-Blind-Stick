#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID    "7b7d1000-3f9b-4f6c-9d8c-2d4a7e901001"
#define ALERT_CHAR_UUID "7b7d1001-3f9b-4f6c-9d8c-2d4a7e901001"

struct Sensor {
  const char *name;
  uint8_t trig;
  uint8_t echo;
  float enterDistance;
  float exitDistance;
  bool blocked;
  unsigned long lastAlertMs;
};

Sensor sensors[] = {
    {"FRONT", 21, 19, 70.0, 82.0, false, 0},
    {"LEFT", 33, 39, 40.0, 50.0, false, 0},
    {"RIGHT", 32, 36, 40.0, 50.0, false, 0},
    {"LOW", 5, 18, 95.0, 110.0, false, 0}};

BLECharacteristic *alertCharacteristic = nullptr;
bool phoneConnected = false;
volatile bool restartAdvertising = false;
unsigned long disconnectedAtMs = 0;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    phoneConnected = true;
    restartAdvertising = false;
    Serial.println("Phone connected");
  }

  void onDisconnect(BLEServer *server) override {
    phoneConnected = false;
    // Do not sleep or restart advertising inside the BLE stack callback.
    // Let the main loop do that once the disconnect has settled.
    disconnectedAtMs = millis();
    restartAdvertising = true;
    Serial.println("Phone disconnected");
  }
};

float readDistanceCm(const Sensor &sensor) {
  digitalWrite(sensor.trig, LOW);
  delayMicroseconds(3);
  digitalWrite(sensor.trig, HIGH);
  delayMicroseconds(10);
  digitalWrite(sensor.trig, LOW);

  // A 30 ms timeout is approximately 5 metres. A timeout is not treated as
  // a close obstacle because there was no valid echo.
  const unsigned long duration = pulseIn(sensor.echo, HIGH, 30000UL);
  if (duration == 0) {
    return 999.0;
  }
  return duration * 0.0343F / 2.0F;
}

void sendMessage(const char *name, float distance) {
  if (!phoneConnected || alertCharacteristic == nullptr) {
    return;
  }

  char message[32];
  snprintf(message, sizeof(message), "%s,%.0f", name, distance);
  alertCharacteristic->setValue(
      reinterpret_cast<uint8_t *>(message), strlen(message));
  alertCharacteristic->notify();
}

void setup() {
  Serial.begin(115200);

  for (Sensor &sensor : sensors) {
    pinMode(sensor.trig, OUTPUT);
    digitalWrite(sensor.trig, LOW);
    pinMode(sensor.echo, INPUT);
  }

  BLEDevice::init("BlindGuide-ESP32");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);
  alertCharacteristic = service->createCharacteristic(
      ALERT_CHAR_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  alertCharacteristic->addDescriptor(new BLE2902());
  alertCharacteristic->setValue("READY");
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->start();

  Serial.println("BlindGuide sensor unit ready");
}

void loop() {
  constexpr unsigned long repeatEveryMs = 2500;

  if (restartAdvertising && !phoneConnected
      && millis() - disconnectedAtMs >= 300) {
    restartAdvertising = false;
    BLEDevice::getAdvertising()->start();
    Serial.println("Advertising again");
  }

  // Trigger only one sensor at a time to reduce ultrasonic cross-talk.
  for (Sensor &sensor : sensors) {
    const float cm = readDistanceCm(sensor);
    const unsigned long now = millis();

    if (!sensor.blocked && cm >= 2.0F && cm < sensor.enterDistance) {
      sensor.blocked = true;
      sensor.lastAlertMs = now;
      sendMessage(sensor.name, cm);
    } else if (sensor.blocked && cm > sensor.exitDistance) {
      sensor.blocked = false;
      // Clear only the sensor that recovered. A global CLEAR made the Android
      // app forget obstacles that were still present on the other sides.
      char clearMessage[24];
      snprintf(clearMessage, sizeof(clearMessage), "CLEAR_%s", sensor.name);
      sendMessage(clearMessage, cm);
    } else if (sensor.blocked && now - sensor.lastAlertMs >= repeatEveryMs) {
      sensor.lastAlertMs = now;
      sendMessage(sensor.name, cm);
    }

    Serial.printf("%s: %.1f cm%s\n", sensor.name, cm,
                  sensor.blocked ? "  OBSTACLE" : "");
    delay(55);
  }
}
