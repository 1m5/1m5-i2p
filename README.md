# I2P Sensor
Invisible Internet Project (I2P) Sensor

## Build Notes
- Required flex-gmss-1.7p1.jar in libs folder to be added to local Maven .m2 directory:
mvn install:install-file -Dfile=flexi-gmss-1.7p1.jar -DgroupId=de.flexi -DartifactId=gmss -Dversion=1.7p1 -Dpackaging=jar
- Required certificates from the following two directories in the i2p.i2p project (I2P Router core)
to be copied to resources/io/onemfive/core/sensors/i2p/bote/certificates keeping reseed and ssl as directories:
    - /installer/resources/certificates/reseed
    - /installer/resources/certificates/ssl
    
## Applications

### I2P Bote
Extends I2P Sensor embedding I2P Bote - adds storable DHT for delayed routing to battle timing attacks.
Started when 1m5.sensors.registered property in sensors.config contains bote.
Launches I2P Sensor if it's not already running.
Has been verified to send and receive messages. 
    
## Attack Mitigation

- https://www.irongeek.com/i.php?page=security/i2p-identify-service-hosts-eepsites
