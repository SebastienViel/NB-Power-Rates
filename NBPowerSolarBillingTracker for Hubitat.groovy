/**
 *  NB Power Solar Billing Tracker
 *
 *  Monitors Emporia Vue energy data to compare the current NB Power net metering
 *  rate structure against the proposed three-part rate (import rate + export credit
 *  + demand charge), pending EUB approval April 1, 2027.
 *
 *  Requires: amithalp/EmporiaV2 integration (or ke7lvb/Emporia-Vue-Hubitat driver)
 *            with child devices exposing PowerMeter and EnergyMeter capabilities.
 *
 *  How it works:
 *    - You select your "Grid Import" Emporia channel (mains CT, grid consumption)
 *    - You select your "Solar Export" Emporia channel (solar CT, energy sent to grid)
 *    - Every minute it reads the current POWER (watts) from both channels to:
 *        a) accumulate kWh manually between billing resets (for import & export)
 *        b) track the peak 15-minute average demand in the billing period
 *    - All running totals are stored in state so they survive hub reboots
 *    - A summary is written to a set of virtual tile attributes for dashboard use
 *    - Notifications can be sent when the peak demand spikes above a threshold
 *
 *  Billing math:
 *    Current net metering : net_kWh × currentRate  (1:1 offset, can go negative)
 *    Proposed structure   : (importKwh × importRate) − (exportKwh × exportRate)
 *                           + (peakKw × demandCharge)
 *
 *  Author : generated for Sébastien with Claude (Anthropic), May 2026
 *  License: Apache 2.0
 */

definition(
    name:        "NB Power Solar Billing Tracker",
    namespace:   "nbpower",
    author:      "Sébastien",
    description: "Tracks solar import/export kWh and 15-min peak demand to compare " +
                 "current NB Power net metering against the proposed rate structure.",
    category:    "Energy Management",
    iconUrl:     "",
    iconX2Url:   ""
)

// ---------------------------------------------------------------------------
// Preferences / UI
// ---------------------------------------------------------------------------
preferences {
    page(name: "mainPage")
    page(name: "ratesPage")
    page(name: "devicesPage")
    page(name: "notificationsPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "NB Power Solar Billing Tracker", install: true, uninstall: true) {

        section("<b>Setup</b>") {
            href "devicesPage",       title: "📡 Energy Devices",      description: devicesDescription(),      required: true
            href "ratesPage",         title: "💲 Rate Settings",       description: ratesDescription(),        required: true
            href "notificationsPage", title: "🔔 Notifications",       description: notifyDescription()
        }

        section("<b>Billing Period</b>") {
            input "billingDay", "number",
                title: "Billing cycle start day of month (e.g. 1 for the 1st)",
                defaultValue: 1, range: "1..28", required: true

            paragraph "Current billing period: <b>${currentBillingPeriodLabel()}</b>"
        }

        section("<b>Dashboard Summary</b>") {
            input "summaryDevice", "capability.actuator",
                title: "Optional: Virtual device to write summary tile attributes to",
                required: false, multiple: false
            paragraph "If set, the attributes <i>nbpCurrentCost</i>, <i>nbpProposedCost</i>, " +
                      "<i>nbpDelta</i>, <i>nbpPeakKw</i>, <i>nbpImportKwh</i>, <i>nbpExportKwh</i> " +
                      "will be sent to this device for use in Hubitat dashboards."
        }

        section("<b>Billing Period Controls</b>") {
            input "resetBtn", "button", title: "🔄 Reset Billing Period Now"
            paragraph "Use this at the start of each new billing cycle to zero counters. " +
                      "The app also resets automatically on the configured billing day."
            paragraph billingStatusParagraph()
        }

        section("<b>Logging</b>") {
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Energy Devices", nextPage: "mainPage") {
        section("""<b>Select Emporia Vue channels</b>
<i>The EmporiaV2 integration creates one child device per circuit. 
Select the channel that measures energy drawn FROM the grid (mains/import) 
and the channel that measures energy sent TO the grid (solar/export).</i>""") {

            input "gridImportDevice", "capability.energyMeter",
                title: "Grid Import channel (energy consumed from grid, kWh)",
                description: "e.g. 'Emporia - Main Panel - Total' or your mains CT device",
                required: true, multiple: false, submitOnChange: true

            input "solarExportDevice", "capability.energyMeter",
                title: "Solar Export channel (energy sent to grid, kWh)",
                description: "e.g. 'Emporia - Solar' or your solar CT device",
                required: true, multiple: false, submitOnChange: true
        }

        section("<b>Power source for demand tracking</b>") {
            paragraph """The demand charge is based on your highest 15-minute average 
power draw FROM the grid (not gross consumption). Select the same device as 
Grid Import above, or a PowerMeter device that shows net grid watts."""

            input "peakPowerDevice", "capability.powerMeter",
                title: "Grid power meter for demand tracking (watts)",
                description: "Should be your Grid Import device or whole-home net meter",
                required: true, multiple: false
        }

        section("<b>Data method</b>") {
            paragraph """<b>kWh accumulation mode:</b> The EmporiaV2 integration reports 
cumulative kWh on the <i>energy</i> attribute. This app can work in two ways:

• <b>Snapshot mode (recommended)</b>: records the kWh reading when the billing 
  period starts, then computes usage as current − start snapshot. Works best 
  when the Emporia integration resets energy counters daily (1D scale).

• <b>Manual accumulation mode</b>: polls watts every minute and integrates to kWh 
  internally. Use if the energy attribute isn't accumulating correctly."""

            input "accumMode", "enum",
                title: "kWh accumulation method",
                options: ["snapshot": "Snapshot (current − baseline)", 
                          "manual":   "Manual (integrate watts × time)"],
                defaultValue: "snapshot", required: true
        }
    }
}

def ratesPage() {
    dynamicPage(name: "ratesPage", title: "Rate Settings", nextPage: "mainPage") {

        section("<b>Current NB Power Net Metering Rate</b>") {
            paragraph "Under the current programme, import and export offset at the same rate (1:1)."
            input "currentNetRate", "decimal",
                title: "Net metering rate (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
        }

        section("<b>Proposed Rate Structure</b>") {
            paragraph """Pending EUB approval for April 1, 2027. Adjust as NB Power 
confirms final numbers."""
            input "proposedImportRate", "decimal",
                title: "Import rate — energy drawn from grid (¢/kWh)",
                defaultValue: 14.76, range: "0.01..99.99", required: true
            input "proposedExportRate", "decimal",
                title: "Export credit — energy sent to grid (¢/kWh)",
                defaultValue: 6.77, range: "0.01..99.99", required: true
            input "demandChargeRate", "decimal",
                title: "Demand charge rate (\$/kW of peak 15-min demand)",
                defaultValue: 13.00, range: "0.01..99.99", required: true
        }

        section("<b>Demand charge window</b>") {
            paragraph """NB Power's proposal applies the demand charge only between 
<b>7:00 am and 10:00 pm</b>. Peaks outside that window are ignored."""
            input "demandWindowStart", "number",
                title: "Window start hour (0–23, default 7 = 7:00 am)",
                defaultValue: 7, range: "0..23", required: true
            input "demandWindowEnd", "number",
                title: "Window end hour (0–23, default 22 = 10:00 pm)",
                defaultValue: 22, range: "0..23", required: true
        }
    }
}

def notificationsPage() {
    dynamicPage(name: "notificationsPage", title: "Notifications", nextPage: "mainPage") {
        section("<b>Demand spike alert</b>") {
            paragraph "Send a notification when instantaneous grid power exceeds a threshold."
            input "notifyDevices", "capability.notification",
                title: "Notification devices", required: false, multiple: true
            input "spikeThresholdKw", "decimal",
                title: "Alert when grid power exceeds (kW)",
                description: "e.g. 8 kW — set to 0 to disable",
                defaultValue: 0, range: "0..100", required: false
            input "spikeAlertCooldownMin", "number",
                title: "Minimum minutes between spike alerts",
                defaultValue: 30, range: "1..1440", required: false
        }

        section("<b>Monthly summary notification</b>") {
            paragraph "Send a billing summary at the start of each new billing period."
            input "sendMonthlySummary", "bool",
                title: "Send monthly summary notification", defaultValue: true
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
def installed() {
    logDebug "Installed"
    initialize()
}

def updated() {
    logDebug "Updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    // Initialise state if first run or after a reset
    if (state.billingStart == null) resetBillingPeriod()

    // Poll every minute for power readings and manual kWh accumulation
    schedule("0 * * * * ?", "everyMinute")

    // Check for auto-reset at midnight on the configured billing day
    schedule("0 0 0 ${billingDay} * ?", "autoBillingReset")

    // Subscribe to energy attribute changes for snapshot mode
    if (accumMode == "snapshot") {
        if (gridImportDevice)  subscribe(gridImportDevice,  "energy", "onGridEnergy")
        if (solarExportDevice) subscribe(solarExportDevice, "energy", "onSolarEnergy")
    }

    logInfo "NB Power Solar Billing Tracker initialised. Billing period: ${currentBillingPeriodLabel()}"
    refreshSummary()
}

// ---------------------------------------------------------------------------
// Button handler
// ---------------------------------------------------------------------------
def appButtonHandler(btn) {
    if (btn == "resetBtn") {
        log.info "Manual billing period reset triggered."
        endOfPeriodSummary()
        resetBillingPeriod()
        refreshSummary()
    }
}

// ---------------------------------------------------------------------------
// Auto reset
// ---------------------------------------------------------------------------
def autoBillingReset() {
    logInfo "Auto billing period reset on day ${billingDay}."
    if (sendMonthlySummary) endOfPeriodSummary()
    resetBillingPeriod()
    refreshSummary()
}

def resetBillingPeriod() {
    def now = now()
    state.billingStart     = now
    state.billingStartStr  = formatDate(now)

    // Snapshot baselines — current energy attribute values
    state.importKwhBase    = safeEnergy(gridImportDevice)
    state.exportKwhBase    = safeEnergy(solarExportDevice)

    // Manual accumulation totals
    state.importKwhManual  = 0.0
    state.exportKwhManual  = 0.0

    // 15-minute window tracking
    state.minuteWatts      = []   // ring buffer of per-minute average watts (up to 15)
    state.peakKw           = 0.0
    state.peakKwTime       = ""

    // Spike alert state
    state.lastSpikeAlert   = 0

    logInfo "Billing period reset. Import baseline: ${state.importKwhBase} kWh, Export baseline: ${state.exportKwhBase} kWh"
}

// ---------------------------------------------------------------------------
// Every-minute polling
// ---------------------------------------------------------------------------
def everyMinute() {
    def nowW = safeWatts(peakPowerDevice)   // current grid watts (positive = consuming)

    // ── Manual kWh accumulation (1 minute = 1/60 hour) ──────────────────
    if (accumMode == "manual") {
        // Only accumulate positive values for each direction
        def importW = (nowW > 0) ? nowW : 0
        def exportW = (nowW < 0) ? Math.abs(nowW) : 0
        state.importKwhManual = (state.importKwhManual ?: 0) + (importW / 1000.0 / 60.0)
        state.exportKwhManual = (state.exportKwhManual ?: 0) + (exportW / 1000.0 / 60.0)
    }

    // ── 15-minute rolling average for demand tracking ────────────────────
    // Only track during the demand window (7 am – 10 pm per NB Power proposal)
    def hr = new Date().hours
    if (hr >= (demandWindowStart ?: 7) && hr < (demandWindowEnd ?: 22)) {
        List buf = state.minuteWatts ?: []
        buf << nowW
        if (buf.size() > 15) buf = buf.drop(buf.size() - 15)
        state.minuteWatts = buf

        if (buf.size() == 15) {
            // We have a full 15-minute window — compute average kW
            def avgKw = (buf.sum() / buf.size()) / 1000.0
            if (avgKw > (state.peakKw ?: 0)) {
                state.peakKw     = avgKw.round(3)
                state.peakKwTime = formatDate(now())
                logDebug "New peak 15-min demand: ${state.peakKw} kW at ${state.peakKwTime}"
            }
        }
    } else {
        // Outside window — reset the rolling buffer so it doesn't carry across boundary
        state.minuteWatts = []
    }

    // ── Spike alert ──────────────────────────────────────────────────────
    def threshKw = (spikeThresholdKw ?: 0) as double
    if (threshKw > 0 && (nowW / 1000.0) > threshKw) {
        def cooldownMs = ((spikeAlertCooldownMin ?: 30) * 60 * 1000) as long
        if ((now() - (state.lastSpikeAlert ?: 0)) > cooldownMs) {
            state.lastSpikeAlert = now()
            def msg = "⚡ Demand spike: grid draw is ${(nowW/1000).round(1)} kW " +
                      "(threshold ${threshKw} kW). Demand charge impact if sustained 15 min: " +
                      "\$${((nowW/1000) * (demandChargeRate ?: 13)).round(2)}."
            sendNotifications(msg)
        }
    }

    // Refresh summary every minute so dashboard stays current
    refreshSummary()
}

// ---------------------------------------------------------------------------
// Energy attribute event handlers (snapshot mode)
// ---------------------------------------------------------------------------
def onGridEnergy(evt)  { refreshSummary() }
def onSolarEnergy(evt) { refreshSummary() }

// ---------------------------------------------------------------------------
// Core calculations
// ---------------------------------------------------------------------------
def importKwh() {
    if (accumMode == "snapshot") {
        def cur  = safeEnergy(gridImportDevice)
        def base = (state.importKwhBase ?: 0) as double
        return Math.max(0, cur - base)
    } else {
        return (state.importKwhManual ?: 0) as double
    }
}

def exportKwh() {
    if (accumMode == "snapshot") {
        def cur  = safeEnergy(solarExportDevice)
        def base = (state.exportKwhBase ?: 0) as double
        return Math.max(0, cur - base)
    } else {
        return (state.exportKwhManual ?: 0) as double
    }
}

def peakKw()     { return (state.peakKw ?: 0) as double }

def currentCost() {
    // Net metering: net kWh × rate (positive = owe money, negative = credit)
    def netKwh = importKwh() - exportKwh()
    return (netKwh * ((currentNetRate ?: 14.76) / 100.0)).round(2)
}

def proposedCost() {
    def importCost  = importKwh()  * ((proposedImportRate  ?: 14.76) / 100.0)
    def exportCredit = exportKwh() * ((proposedExportRate   ?: 6.77)  / 100.0)
    def demandCost  = peakKw()     *  (demandChargeRate     ?: 13.00)
    return (importCost - exportCredit + demandCost).round(2)
}

def delta() {
    return (proposedCost() - currentCost()).round(2)
}

// ---------------------------------------------------------------------------
// Summary / dashboard
// ---------------------------------------------------------------------------
def refreshSummary() {
    def imp   = importKwh().round(2)
    def exp   = exportKwh().round(2)
    def peak  = peakKw().round(3)
    def cur   = currentCost()
    def prop  = proposedCost()
    def delt  = delta()
    def sign  = delt >= 0 ? "+" : ""

    // Log summary
    logDebug "=== Billing Summary === Import: ${imp} kWh | Export: ${exp} kWh | " +
             "Peak: ${peak} kW | Current cost: \$${cur} | Proposed: \$${prop} | Delta: ${sign}\$${delt}"

    // Push to optional virtual tile device
    if (summaryDevice) {
        sendEvent(summaryDevice, [name: "nbpImportKwh",    value: imp,   unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpExportKwh",    value: exp,   unit: "kWh"])
        sendEvent(summaryDevice, [name: "nbpPeakKw",       value: peak,  unit: "kW"])
        sendEvent(summaryDevice, [name: "nbpCurrentCost",  value: cur,   unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpProposedCost", value: prop,  unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpDelta",        value: "${sign}${delt}", unit: "\$"])
        sendEvent(summaryDevice, [name: "nbpPeakKwTime",   value: state.peakKwTime ?: "—"])
        sendEvent(summaryDevice, [name: "nbpBillingStart", value: state.billingStartStr ?: "—"])
    }
}

def endOfPeriodSummary() {
    def imp   = importKwh().round(2)
    def exp   = exportKwh().round(2)
    def peak  = peakKw().round(3)
    def cur   = currentCost()
    def prop  = proposedCost()
    def delt  = delta()
    def sign  = delt >= 0 ? "+" : ""

    def msg = """📊 NB Power Billing Period Summary
Period: ${state.billingStartStr} – ${formatDate(now())}
─────────────────────────────
Grid import:  ${imp} kWh
Solar export: ${exp} kWh
Net kWh:      ${(imp - exp).round(2)} kWh
Peak 15-min demand: ${peak} kW (${state.peakKwTime ?: "not recorded"})
─────────────────────────────
Current net metering: \$${cur}
Proposed structure:   \$${prop}
  • Import (${imp} kWh × ${proposedImportRate}¢):  \$${(imp * proposedImportRate / 100).round(2)}
  • Export credit (${exp} kWh × ${proposedExportRate}¢): -\$${(exp * proposedExportRate / 100).round(2)}
  • Demand charge (${peak} kW × \$${demandChargeRate}): \$${(peak * demandChargeRate).round(2)}
─────────────────────────────
Proposed vs. current: ${sign}\$${delt} (${delt >= 0 ? "MORE expensive" : "less expensive"})
Annualised projection: ${sign}\$${(delt * 12).round(0)}/yr"""

    logInfo msg
    sendNotifications(msg)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
def safeEnergy(dev) {
    try {
        def v = dev?.currentValue("energy")
        return (v != null) ? (v as double) : 0.0
    } catch (e) { return 0.0 }
}

def safeWatts(dev) {
    try {
        def v = dev?.currentValue("power")
        return (v != null) ? (v as double) : 0.0
    } catch (e) { return 0.0 }
}

def sendNotifications(String msg) {
    notifyDevices?.each { it.deviceNotification(msg) }
}

def formatDate(long ts) {
    return new Date(ts).format("yyyy-MM-dd HH:mm", location.timeZone)
}

def currentBillingPeriodLabel() {
    def now = new Date()
    def day = now.date
    def yr  = now.year + 1900
    def mo  = now.month  // 0-based
    def bd  = (billingDay ?: 1) as int

    Date start
    if (day >= bd) {
        start = Date.parse("yyyy-M-d", "${yr}-${mo + 1}-${bd}")
    } else {
        // Previous month
        def cal = Calendar.getInstance()
        cal.set(yr, mo - 1, bd)
        start = cal.time
    }
    def end = start + 30  // approximate
    return start.format("MMM d") + " – " + end.format("MMM d, yyyy")
}

def billingStatusParagraph() {
    def imp   = importKwh().round(2)
    def exp   = exportKwh().round(2)
    def peak  = peakKw().round(3)
    def cur   = currentCost()
    def prop  = proposedCost()
    def delt  = delta()
    def sign  = delt >= 0 ? "+" : ""

    return """<b>Billing period started:</b> ${state.billingStartStr ?: "(not set)"}
<br><b>Import:</b> ${imp} kWh &nbsp; <b>Export:</b> ${exp} kWh
<br><b>Peak 15-min demand:</b> ${peak} kW (at ${state.peakKwTime ?: "—"})
<br><b>Current net metering cost:</b> \$${cur}
<br><b>Proposed cost:</b> \$${prop} &nbsp; <b>Δ</b> ${sign}\$${delt}"""
}

def devicesDescription() {
    if (!gridImportDevice || !solarExportDevice || !peakPowerDevice) return "Tap to configure ←"
    return "Import: ${gridImportDevice.displayName}\nExport: ${solarExportDevice.displayName}"
}

def ratesDescription() {
    return "Net: ${currentNetRate}¢ | Import: ${proposedImportRate}¢ | Export: ${proposedExportRate}¢ | Demand: \$${demandChargeRate}/kW"
}

def notifyDescription() {
    if (!notifyDevices) return "No notifications configured"
    return "${notifyDevices.size()} device(s) | Spike threshold: ${spikeThresholdKw ?: 'off'} kW"
}

def logDebug(msg) { if (debugLog) log.debug "[NBPSolar] ${msg}" }
def logInfo(msg)  { log.info  "[NBPSolar] ${msg}" }
