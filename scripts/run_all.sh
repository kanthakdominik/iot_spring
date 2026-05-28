#!/bin/bash

echo "Rozpoczynam generowanie wyników zgodnie z README..."

mkdir -p results

echo "-> 1/8: Test GPS - poprawne"
groovy scripts/gps_test_calculation.groovy measurements/gps_test_correct.csv > results/gps_test_correct_result.md

echo "-> 2/8: Test GPS - niepoprawne"
groovy scripts/gps_test_calculation.groovy measurements/gps_test_incorrect.csv > results/gps_test_incorrect_result.md

echo "-> 3/8: Pomiary stacjonarne"
groovy scripts/route_stationary_metrics.groovy measurements/data_stationary.csv > results/data_stationary_result.md

echo "-> 4/8: Pomiary piesze"
groovy scripts/route_mobile_metrics.groovy measurements/data_pedestrian.csv 0.13 > results/data_pedestrian_result.md

echo "-> 5/8: Pomiary mobilne miasto"
groovy scripts/route_mobile_metrics.groovy measurements/data_city.csv 0.13 > results/data_city_result.md

echo "-> 6/8: Pomiary mobilne miedzymiastowe"
groovy scripts/route_mobile_metrics.groovy measurements/data_intercity.csv 0.13 > results/data_intercity_result.md

echo "-> 7/8: Pomiary żródło 50cm"
#groovy scripts/route_source_metrics.groovy measurements/data_source_50.csv > results/data_source_50_result.md
#
echo "-> 8/8: Pomiary żródło 100cm"
#groovy scripts/route_source_metrics.groovy measurements/data_source_100.csv > results/data_source_100_result.md

echo ""
echo "=== Zakończono wykonywanie wszystkich pomiarów! Wyniki są w folderze 'results/'. ==="
