# iot_spring

## Uruchomienie skryptu metryk GPS

# Test GPS

```bash
groovy scripts/gps_test_calculation.groovy measurements/gps_test_correct.csv > results/gps_test_correct_result.md
```
```bash
groovy scripts/gps_test_calculation.groovy measurements/gps_test_incorrect.csv > results/gps_test_incorrect_result.md
```

# Pomiary stacjonarne
```bash
groovy scripts/route_stationary.groovy measurements/data_stationary.csv > results/data_stationary_result.md
```

# Pomiary piesze
```bash
groovy scripts/route_mobile_metrics.groovy measurements/data_pedestrian.csv > results/data_pedestrian_result.md 0.12
```

# Pomiary mobilne miasto
```bash
groovy scripts/route_mobile_metrics.groovy measurements/data_city.csv > results/data_city_result.md 0.13
```

# Pomiary mobilne miedzymiastowe
```bash
groovy scripts/route_mobile_metrics.groovy measurements/data_intercity.csv > results/data_intercity_result.md 0.12
```

# Pomiary żródło 50cm 
```bash
groovy scripts/route_source_metrics.groovy measurements/data_source_50.csv > results/data_source_50_result.md
```

# Pomiary żródło 100cm 
```bash
groovy scripts/route_source_metrics.groovy measurements/data_source_100.csv > results/data_source_100_result.md
```

# Analiza środków promieniowania w odniesieniu do prawdziwego źródła (dla 50cm i 100cm)
```bash
groovy scripts/routes_spatial_compare.groovy measurements/data_source_50.csv measurements/data_source_100.csv > results/data_radiation_centers_info.md
```