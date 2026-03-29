# Scanner Bridge — Installation Guide
> Follow these steps to set up Scanner Bridge on your computer.
> This only needs to be done **once**.

---

## What is Scanner Bridge?

Scanner Bridge is a small background program that lets you scan documents directly from your web browser. Once installed, it runs automatically in the background — you will never need to open or manage it manually.

---

## Before You Begin

Make sure the following are ready:

- Your flatbed scanner is **plugged in** and **turned on**
- You are using **Google Chrome** as your browser
- You have access to an **administrator account** on your computer (you will be asked during installation)

---

## Installation Steps

### Step 1 — Run the installer

Double-click the file you were given:

```
ScannerBridgeSetup.exe
```

> If Windows shows a warning that says *"Windows protected your PC"*, click **More info** then **Run anyway**. This message appears for software that is not yet widely distributed — Scanner Bridge is safe to install.

---

### Step 2 — Approve the administrator request

A box will appear asking:

> *"Do you want to allow this app to make changes to your device?"*

Click **Yes**.

This is required because Scanner Bridge installs as a Windows Service, which needs administrator permission.

---

### Step 3 — Complete the installation wizard

Click **Next** on each screen, then click **Install**.

The installation takes less than one minute.

When the final screen appears, click **Finish**.

---

### Step 4 — You are done

Scanner Bridge is now installed and running in the background.

You do not need to do anything else. Open your web application in Chrome and you will be able to scan documents immediately.

---

## How It Works After Installation

- Scanner Bridge starts **automatically** every time your computer turns on
- It runs **silently in the background** — there is no icon, no window, nothing to open
- You never need to start, stop, or manage it yourself
- If your computer restarts, Scanner Bridge restarts with it automatically

---

## How to Scan

1. Open your web application in **Google Chrome**
2. Navigate to the section where scanning is available
3. Select your preferred file format (**PDF**, **JPEG**, **PNG**, or **TIFF**)
4. Click **Scan**
5. Your scanner will activate and the scanned file will appear on screen
6. Click **Download** to save the file

---

## Troubleshooting

### "Not connected" message in the web app

Scanner Bridge may not be running. Try the following:

1. Press **Windows + R**, type `services.msc`, press **Enter**
2. Scroll down to find **Scanner Bridge**
3. If the Status column is blank (not "Running"), right-click it and select **Start**
4. Refresh the web page and try again

If the service is not listed at all, re-run the installer (`ScannerBridgeSetup.exe`).

---

### Scanner is not responding

1. Check that your scanner is turned on and connected via USB
2. Try unplugging the USB cable and plugging it back in
3. Restart the Scanner Bridge service (see above) and try again

---

### Scan is slow or times out

Document scanning can take 10–30 seconds depending on your scanner and the document size. Wait for the scan to complete before clicking anything else. If it times out, try again — the first scan after the scanner has been idle sometimes takes longer.

---

### "Connection failed" or page shows scanner as unavailable

This may mean the Scanner Bridge service stopped unexpectedly. Try:

1. Press **Windows + R**, type `services.msc`, press **Enter**
2. Find **Scanner Bridge** and click **Start**
3. Refresh the web page

If restarting the service does not help, contact your IT helpdesk.

---

## Uninstalling

If you need to remove Scanner Bridge:

1. Open the **Start Menu** and search for **"Add or Remove Programs"**
2. Find **Scanner Bridge** in the list
3. Click it and select **Uninstall**
4. Approve the administrator request
5. Scanner Bridge and the background service will be completely removed

---

## Getting Help

If you continue to experience issues, contact your IT helpdesk and mention that Scanner Bridge is installed as a **Windows Service** named `ScannerBridge`. Your IT team can check the service logs at:

```
C:\Program Files\Scanner Bridge\ScannerBridgeService.out.log
```
