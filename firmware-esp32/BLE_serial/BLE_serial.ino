#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// UUID standard Nordic UART Service (compatibili con Serial Bluetooth Terminal)
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_RX "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// Pin UART verso Raspberry Pi Pico (GP0 -> GPIO20, GP1 -> GPIO21)
#define RX_PIN 20
#define TX_PIN 21

BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Gestione connessione/disconnessione client BLE
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
    }
};

// Callback eseguita automaticamente all'arrivo di dati dal telefono
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String rxValue = pCharacteristic->getValue();

      if (rxValue.length() > 0) {
        // Rimuove eventuali \r o \n residui per standardizzare il fine riga
        rxValue.trim();
        
        if (rxValue.length() > 0) {
          // Inoltra al Raspberry Pi Pico con terminatore \n
          Serial1.println(rxValue);
          // Log di debug su USB (opzionale)
          Serial.println("[BLE -> PICO] " + rxValue);
        }
      }
    }
};

void setup() {
  Serial.begin(115200);
  // Inizializza UART1 verso il Pico
  Serial1.begin(115200, SERIAL_8N1, RX_PIN, TX_PIN);

  // Inizializza BLE con nome visibile dallo smartphone
  BLEDevice::init("Pico-HID-Bridge");
  // Aumenta la dimensione massima dei pacchetti BLE (utile per //WRITE)
  BLEDevice::setMTU(512);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Caratteristica TX (dal Pico/ESP32 verso lo Smartphone)
  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());

  // Caratteristica RX (dallo Smartphone verso ESP32/Pico)
  BLECharacteristic *pRxCharacteristic = pService->createCharacteristic(
                                           CHARACTERISTIC_UUID_RX,
                                           BLECharacteristic::PROPERTY_WRITE | 
                                           BLECharacteristic::PROPERTY_WRITE_NR
                                         );
  pRxCharacteristic->setCallbacks(new MyCallbacks());

  pService->start();
  pServer->getAdvertising()->start();
  Serial.println("Bridge BLE-UART avviato. In attesa di connessione...");
}

void loop() {
  // 1. Leggi risposte, ACK ('#') o output dal Pico e girali al telefono
  if (Serial1.available()) {
    String picoReply = Serial1.readStringUntil('\n');
    picoReply.trim();
    
    if (picoReply.length() > 0) {
      Serial.println("[PICO -> BLE] " + picoReply);
      
      if (deviceConnected) {
        String payload = picoReply + "\n";
        pTxCharacteristic->setValue(payload.c_str());
        pTxCharacteristic->notify();
      }
    }
  }

  // 2. Riavvia l'advertising se il telefono si disconnette
  if (!deviceConnected && oldDeviceConnected) {
    delay(500); 
    pServer->startAdvertising(); 
    Serial.println("Client disconnesso. Advertising riavviato.");
    oldDeviceConnected = deviceConnected;
  }
  
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
    Serial.println("Smartphone connesso!");
  }

  delay(5);
}