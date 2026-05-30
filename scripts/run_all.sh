#!/bin/bash

THRESHOLD="0.15"
PEAK_FACTOR="0.8"
SOURCE_LAT="52.218280627634556"
SOURCE_LON="21.010925227559188"

echo "Rozpoczynam generowanie wyników zgodnie z README..."

mkdir -p results

echo "-> 1/8: Test GPS - poprawne"
groovy scripts/gps_test_calculation.groovy measurements/gps_test_correct.csv > results/gps_test_correct_result.md

echo "-> 2/8: Test GPS - niepoprawne"
groovy scripts/gps_test_calculation.groovy measurements/gps_test_incorrect.csv > results/gps_test_incorrect_result.md

echo "-> 3/8: Pomiary stacjonarne"
groovy scripts/route_stationary_metrics.groovy measurements/data_stationary.csv > results/data_stationary_result.md

echo "-> 4/8: Pomiary piesze"
groovy scripts/route_mobile_metrics.groovy measurements/data_pedestrian.csv $THRESHOLD > results/data_pedestrian_result.md

echo "-> 5/8: Pomiary mobilne miasto"
groovy scripts/route_mobile_metrics.groovy measurements/data_city.csv $THRESHOLD > results/data_city_result.md

echo "-> 6/8: Pomiary mobilne miedzymiastowe"
groovy scripts/route_mobile_metrics.groovy measurements/data_intercity.csv $THRESHOLD > results/data_intercity_result.md

echo "-> 7/8: Pomiary żródło 50cm"
groovy scripts/route_source_metrics.groovy measurements/data_source_50.csv $THRESHOLD $PEAK_FACTOR > results/data_source_50_result.md

echo "-> 8/8: Pomiary żródło 100cm"
groovy scripts/route_source_metrics.groovy measurements/data_source_100.csv $THRESHOLD $PEAK_FACTOR > results/data_source_100_result.md

echo "-> 9/9: Porównanie przestrzenne tras względem rzeczywistego źródła"
groovy scripts/routes_spatial_compare.groovy measurements/data_source_50.csv measurements/data_source_100.csv $SOURCE_LAT $SOURCE_LON $THRESHOLD $PEAK_FACTOR > results/spatial_comparing_routes.md

echo ""
echo "=== Zakończono wykonywanie wszystkich pomiarów! Wyniki są w folderze 'results/'. ==="
