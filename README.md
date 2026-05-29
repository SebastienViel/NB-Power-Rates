# NB Power Solar Billing Tracker for Hubitat

A Hubitat Elevation app that monitors your Emporia Vue energy data and compares your **current NB Power net metering costs** against the **proposed three-part rate structure**, pending approval by the New Brunswick Energy and Utilities Board (EUB) for April 1, 2027.

-----

## Background

NB Power has filed a General Rate Application proposing significant changes to how solar net metering customers are billed. The proposed structure replaces the simple 1:1 kWh offset with three separate components:

|Component                      |Current                      |Proposed                               |
|-------------------------------|-----------------------------|---------------------------------------|
|Energy drawn from grid         |offset by export at same rate|billed at import rate (¢/kWh)          |
|Energy sent to grid (solar)    |offsets import 1:1           |credited at a lower export rate (¢/kWh)|
|Peak 15-min demand (7 am–10 pm)|not applicable               |$13.00 per kW                          |

The demand charge — applied to the single highest 15-minute average grid draw in the billing period — is the most impactful and unpredictable element. NB Power’s own example (8.4 kW peak) results in a bill roughly $90 higher per month than under the current programme.

Changes are **not yet in effect**. The EUB review process includes public hearings. Monitor proceedings at [nbeub.ca](https://www.nbeub.ca).

-----

## What This App Does

- Reads watts in real time from your two Emporia Vue mains channels (from-grid and to-grid)
- Integrates watts × time every minute to accumulate kWh totals for the billing period
- Tracks the highest 15-minute average grid demand within the 7 am–10 pm window
- Calculates and displays both cost models side by side at all times
- Resets automatically at the start of each billing period
- Sends notifications for demand spikes and monthly billing summaries
- Writes live attributes to a virtual device for Hubitat dashboard tiles

-----

## Requirements

- **Hubitat Elevation** hub (any model)
- **Emporia Vue** energy monitor with CT clamps on your mains circuits
- **Emporia Vue integration for Hubitat** — either:
  - [amithalp/EmporiaV2](https://github.com/amithalp/EmporiaV2) *(recommended)*
  - [ke7lvb/Emporia-Vue-Hubitat](https://github.com/ke7lvb/Emporia-Vue-Hubitat)

Your Emporia integration must create Hubitat child devices that expose the **PowerMeter** capability (`power` attribute in watts). No EnergyMeter (kWh) capability is required.

-----

## Installation

### Step 1 — Install the Emporia Vue integration

If you haven’t already, install one of the Emporia integrations listed above and verify that your mains circuits appear as devices in Hubitat with a live `power` (watts) reading.

You should have **two relevant devices**:

- **Mains FROM grid** — watts flowing from the utility into your home
- **Mains TO grid** — watts flowing from your solar panels out to the grid

### Step 2 — Install this app

**Option A — Import URL (easiest)**

1. In Hubitat, go to **Apps Code → + New App**
1. Click **Import** and paste this URL:
   
   ```
   https://raw.githubusercontent.com/SebastienViel/NB-Power-Rates/main/NBPowerSolarBillingTracker%20for%20Hubitat.groovy
   ```
1. Click **Import**, then **Save**

**Option B — Manual paste**

1. In Hubitat, go to **Apps Code → + New App**
1. Copy the full contents of [`NBPowerSolarBillingTracker for Hubitat.groovy`](./NBPowerSolarBillingTracker%20for%20Hubitat.groovy)
1. Paste into the editor and click **Save**

### Step 3 — Add the app

1. Go to **Apps → + Add User App**
1. Select **NB Power Solar Billing Tracker**
1. Work through the four setup sections below

-----

## Configuration

### 📡 Energy Devices

|Setting        |Description                                                     |
|---------------|----------------------------------------------------------------|
|Mains FROM grid|The Emporia channel measuring watts drawn from the utility      |
|Mains TO grid  |The Emporia channel measuring watts your solar sends to the grid|

Both inputs accept `PowerMeter` capability devices. If your export channel reads **negative** watts during export (rather than positive), open an issue and a sign-flip preference can be added.

### 💲 Rate Settings

|Setting               |Default      |Description                                      |
|----------------------|-------------|-------------------------------------------------|
|Net metering rate     |14.76 ¢/kWh  |Current NB Power 1:1 offset rate                 |
|Proposed import rate  |14.76 ¢/kWh  |Rate charged for grid consumption under new model|
|Proposed export credit|6.77 ¢/kWh   |Credit received for solar export under new model |
|Demand charge rate    |$13.00/kW    |Applied to peak 15-min demand                    |
|Demand window start   |7 (7:00 am)  |Start of the demand tracking window              |
|Demand window end     |22 (10:00 pm)|End of the demand tracking window                |

Update these as NB Power’s proposed rates are confirmed through the EUB process.

### 🔔 Notifications

|Setting             |Description                                                 |
|--------------------|------------------------------------------------------------|
|Notification devices|Hubitat notification devices (mobile app, Pushover, etc.)   |
|Spike threshold (kW)|Alert when grid import exceeds this level — set 0 to disable|
|Spike cooldown (min)|Minimum gap between spike alerts (default 30 min)           |
|Monthly summary     |Send a full billing breakdown at each period rollover       |

### Billing Period

Set the **day of the month** your NB Power billing cycle starts. The app resets all counters automatically at midnight on that day and (optionally) sends a summary notification first.

You can also trigger a manual reset anytime using the **🔄 Reset Billing Period Now** button on the main page.

-----

## Dashboard Integration

Create a **Virtual Device** in Hubitat (type: `Virtual Omnisensor` or any actuator) and select it in the **Dashboard Summary** setting. The app writes the following attributes to it every minute:

|Attribute        |Description                                            |
|-----------------|-------------------------------------------------------|
|`nbpImportKwh`   |kWh consumed from the grid this billing period         |
|`nbpExportKwh`   |kWh sent to the grid this billing period               |
|`nbpPeakKw`      |Highest 15-min average demand recorded (kW)            |
|`nbpCurrentCost` |Estimated bill under current net metering ($)          |
|`nbpProposedCost`|Estimated bill under proposed structure ($)            |
|`nbpDelta`       |Difference: proposed minus current (+ = more expensive)|
|`nbpPeakKwTime`  |Timestamp when peak demand was recorded                |
|`nbpBillingStart`|Start date/time of the current billing period          |

Add these as **Attribute** tiles in any Hubitat dashboard.

-----

## How kWh and Demand Are Calculated

**kWh accumulation**

Every minute, the app reads the `power` attribute (watts) from each device. It converts this to a kWh increment using:

```
kWh increment = watts / 1000 / 60
```

These increments are added to running totals (`importKwh`, `exportKwh`) stored in app state, which survive hub reboots.

**Peak 15-minute demand**

The last 15 one-minute import watt readings are held in a rolling buffer. When the buffer is full, the average is computed and converted to kW. If this beats the stored peak for the period, it is recorded along with its timestamp. Only readings within the configured demand window (default 7 am–10 pm) are included — the buffer clears outside those hours.

**Billing formulas**

```
Current net metering cost = (importKwh - exportKwh) × currentRate
                            (negative = credit carried forward)

Proposed cost = (importKwh × importRate)
              - (exportKwh × exportCredit)
              + (peakKw    × demandCharge)
```

-----

## Companion Web Calculator

A standalone interactive web calculator (React) is also in this repository. It lets you model different usage scenarios without needing Hubitat, and includes a billing log to track months manually.

-----

## Regulatory Status

The proposed rate changes are part of NB Power’s General Rate Application before the **New Brunswick Energy and Utilities Board (EUB)**. If approved, they would take effect **April 1, 2027**. The EUB process includes public hearings — Solar NB Solaire and other groups are actively intervening. Monitor proceedings at [nbeub.ca](https://www.nbeub.ca) and consider participating.

-----

## License

Apache 2.0 — see [LICENSE](./LICENSE) for details.

-----

## Acknowledgements

Built with assistance from Claude (Anthropic). Emporia Vue integration by [amithalp](https://github.com/amithalp/EmporiaV2) and [ke7lvb](https://github.com/ke7lvb/Emporia-Vue-Hubitat).
