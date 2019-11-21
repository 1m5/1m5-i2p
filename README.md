# I2P Sensor
Invisible Internet Project (I2P) Sensor

## Build Notes
- Required certificates from the following two directories in the i2p.i2p project (I2P Router core)
to be copied to resources/io/onemfive/core/sensors/i2p/bote/certificates keeping reseed and ssl as directories:
    - /installer/resources/certificates/reseed
    - /installer/resources/certificates/ssl
    
## Installation


## Removal

### Linux

#### I2P Router
sudo apt remove i2p
sudo apt remove i2prouter
sudo apt autoremove
sudo apt autoclean
    
## Attack Mitigation

- https://www.irongeek.com/i.php?page=security/i2p-identify-service-hosts-eepsites

## Version Notes

### 0.6.0
- upgraded to 0.9.37

### 0.6.2
- upgraded to 0.9.41
- updated reseed and ssl certificates
- added host.txt for reference
- added blocklist.txt for reference

Note: I believe built-in-peers.txt is no longer used; couldn't find an update