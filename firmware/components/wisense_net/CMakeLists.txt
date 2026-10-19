idf_component_register(SRCS "wisense_net.c"
                       INCLUDE_DIRS "include"
                       REQUIRES lwip esp_wifi esp_netif esp_event nvs_flash log)
