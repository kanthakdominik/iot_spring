#!/usr/bin/env groovy

System.setProperty('file.encoding', 'UTF-8')
System.out = new PrintStream(System.out, true, 'UTF-8')
System.err = new PrintStream(System.err, true, 'UTF-8')

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

if (args.length < 1) {
    System.err.println "Użycie: groovy gps_metrics.groovy <plik.csv> [nazwa_trasy]"
    System.exit(1)
}

def inputFile = new File(args[0])

if (!inputFile.exists()) {
    System.err.println "Nie znaleziono pliku: ${args[0]}"
    System.exit(1)
}

def routeName = args.length > 1 ? args[1] : inputFile.name

def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
def rows = []

def parseCsvLine = { String line ->
    def result = []
    def current = new StringBuilder()
    boolean inQuotes = false

    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i)

        if (c == '"') {
            inQuotes = !inQuotes
        } else if (c == ',' && !inQuotes) {
            result << current.toString()
            current.setLength(0)
        } else {
            current.append(c)
        }
    }

    result << current.toString()

    result.collect { it.trim() }
}

inputFile.withReader('UTF-8') { reader ->

    def header = parseCsvLine(reader.readLine())

    def indexes = [
            latitude : header.indexOf('"latitude"') >= 0 ? header.indexOf('"latitude"') : header.indexOf('latitude'),
            longitude: header.indexOf('"longitude"') >= 0 ? header.indexOf('"longitude"') : header.indexOf('longitude'),
            timestamp: header.indexOf('"timestamp"') >= 0 ? header.indexOf('"timestamp"') : header.indexOf('timestamp'),
            cps      : header.indexOf('"cps"') >= 0 ? header.indexOf('"cps"') : header.indexOf('cps'),
            cpm      : header.indexOf('"cpm"') >= 0 ? header.indexOf('"cpm"') : header.indexOf('cpm'),
            usv      : header.indexOf('"usv_per_hr"') >= 0 ? header.indexOf('"usv_per_hr"') : header.indexOf('usv_per_hr')
    ]

    reader.eachLine { line ->

        if (!line?.trim()) {
            return
        }

        def cols = parseCsvLine(line)

        rows << [
                latitude : cols[indexes.latitude].replace('"', '') as BigDecimal,
                longitude: cols[indexes.longitude].replace('"', '') as BigDecimal,
                timestamp: LocalDateTime.parse(cols[indexes.timestamp].replace('"', ''), formatter),
                cps      : indexes.cps >= 0 ? cols[indexes.cps].replace('"', '') as Integer : null,
                cpm      : indexes.cpm >= 0 ? cols[indexes.cpm].replace('"', '') as Integer : null,
                usv      : indexes.usv >= 0 ? cols[indexes.usv].replace('"', '') as BigDecimal : null
        ]
    }
}

if (rows.isEmpty()) {
    System.err.println "Brak danych w pliku CSV"
    System.exit(1)
}

double earthRadius = 6371000.0d

def haversine = { double lat1, double lon1, double lat2, double lon2 ->

    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)

    double a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) *
                    Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) *
                    Math.sin(dLon / 2)

    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

    earthRadius * c
}

double meanLat = rows.collect { it.latitude as double }.sum() / rows.size()
double meanLon = rows.collect { it.longitude as double }.sum() / rows.size()

def distances = rows.collect { row ->
    haversine(
            row.latitude as double,
            row.longitude as double,
            meanLat,
            meanLon
    )
}

def successiveDistances = []

for (int i = 1; i < rows.size(); i++) {

    successiveDistances << haversine(
            rows[i - 1].latitude as double,
            rows[i - 1].longitude as double,
            rows[i].latitude as double,
            rows[i].longitude as double
    )
}

def sortedDistances = distances.sort()

int idx95 = Math.ceil(sortedDistances.size() * 0.95d) as int
idx95 = Math.max(1, Math.min(idx95, sortedDistances.size())) - 1

def latValues = rows.collect { it.latitude as double }
def lonValues = rows.collect { it.longitude as double }

def minLat = latValues.min()
def maxLat = latValues.max()

def minLon = lonValues.min()
def maxLon = lonValues.max()

def duration = Duration.between(
        rows.first().timestamp,
        rows.last().timestamp
)

def avgCpm = rows
        .findAll { it.cpm != null }
        .collect { it.cpm as double }

def avgUsv = rows
        .findAll { it.usv != null }
        .collect { it.usv as double }

def plNumber = { Number value, int scale = 2 ->
    String.format(java.util.Locale.US, "%.${scale}f", value)
            .replace('.', ',')
}

def plDateTime = { LocalDateTime dt ->
    dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
}

def plDuration = { Duration d ->

    long hours = d.toHours()
    long minutes = d.toMinutesPart()
    long seconds = d.toSecondsPart()

    if (hours > 0) {
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    return String.format("%02d:%02d", minutes, seconds)
}

println "# Metryki GPS dla trasy `${routeName}`"
println ""

println "## Podstawowe informacje"
println ""

println "| Metryka | Wartość |"
println "|---|---|"

println "| Liczba próbek | ${rows.size()} |"

println "| Czas początku | ${plDateTime(rows.first().timestamp)} |"

println "| Czas końca | ${plDateTime(rows.last().timestamp)} |"

println "| Czas trwania | ${plDuration(duration)} |"

println "| Środek klastra | ${plNumber(meanLat, 6)}, ${plNumber(meanLon, 6)} |"

println "| Zakres latitude | ${plNumber(minLat, 6)} - ${plNumber(maxLat, 6)} |"

println "| Zakres longitude | ${plNumber(minLon, 6)} - ${plNumber(maxLon, 6)} |"

println "| Średnia odległość od środka [m] | ${plNumber(distances.sum() / distances.size())} |"

println "| Maksymalna odległość od środka [m] | ${plNumber(distances.max())} |"

println "| Percentyl 95 odległości od środka [m] | ${plNumber(sortedDistances[idx95])} |"

if (!successiveDistances.isEmpty()) {
    def avgSucc = successiveDistances.sum() / successiveDistances.size()
    def maxSucc = successiveDistances.max()
    println "| Średnia odległość między kolejnymi punktami [m] | ${plNumber(avgSucc)} |"
    println "| Maksymalna odległość między kolejnymi punktami [m] | ${plNumber(maxSucc)} |"
}

if (!avgCpm.isEmpty()) {
    def avgC = avgCpm.sum() / avgCpm.size()
    println "| Średni CPM | ${plNumber(avgC)} |"
}

if (!avgUsv.isEmpty()) {
    def avgU = avgUsv.sum() / avgUsv.size()
    println "| Średni poziom µSv/h | ${plNumber(avgU, 3)} |"
}

println ""
println "## Tabela do pracy"
println ""

println "| Trasa | Liczba próbek | Czas trwania | Średnia odległość od środka [m] | Maksymalna odległość od środka [m] | Percentyl 95 [m] | Średnia odległość między kolejnymi punktami [m] |"

println "|---|---|---|---|---|---|---|"

def avgSuccStr = successiveDistances.isEmpty() ? '' : plNumber(successiveDistances.sum() / successiveDistances.size())
def avgDistCenter = plNumber(distances.sum() / distances.size())
def maxDistCenter = plNumber(distances.max())
def perc95Center = plNumber(sortedDistances[idx95])

println "| ${routeName} | " +
        "${rows.size()} | " +
        "${plDuration(duration)} | " +
        "${avgDistCenter} | " +
        "${maxDistCenter} | " +
        "${perc95Center} | " +
        "${avgSuccStr} |"
