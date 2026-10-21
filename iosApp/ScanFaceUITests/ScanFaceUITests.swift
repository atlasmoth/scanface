import XCTest

final class ScanFaceUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testApplicationLaunchesAndExposesNavigation() {
        let app = XCUIApplication()
        app.launchArguments = ["--uitesting-reset"]
        app.launch()
        XCTAssertTrue(app.staticTexts["ScanFace"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Home"].exists || app.buttons["Home"].exists)
        XCTAssertTrue(app.staticTexts["History"].exists || app.buttons["History"].exists)
    }

    func testThemePersistsAndEmptyHistoryOffersCapture() {
        let app = XCUIApplication()
        app.launchArguments = ["--uitesting-reset"]
        app.launch()
        tap("Settings", in: app)
        tap("Dark", in: app)
        XCTAssertTrue(element("Dark", in: app).isSelected, app.debugDescription)
        app.terminate()
        app.launchArguments = []
        app.launch()
        tap("Settings", in: app)
        XCTAssertTrue(element("Dark", in: app).isSelected, "Explicit dark appearance should survive relaunch.")
        tap("Light", in: app)
        XCTAssertTrue(element("Light", in: app).isSelected)
        tap("Back", in: app)
        tap("History", in: app)
        XCTAssertTrue(app.staticTexts["No scans yet"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("New face scan", in: app).exists)
        XCTAssertFalse(app.buttons["Export scan history"].exists)
        tap("Home", in: app)
        XCTAssertTrue(app.staticTexts["Start with a face scan"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("Take your first scan", in: app).exists)
        XCTAssertFalse(app.staticTexts["Latest scan"].exists)
    }

    func testCameraCanOpenPickerAndReturnToHome() {
        let app = XCUIApplication()
        app.resetAuthorizationStatus(for: .camera)
        app.launchArguments = ["--uitesting-reset"]
        app.launch()
        addUIInterruptionMonitor(withDescription: "Camera permission") { alert in
            let deny = alert.buttons["Don’t Allow"]
            if deny.exists { deny.tap(); return true }
            return false
        }
        tap("Scan", in: app)
        let choose = app.buttons["camera.import"]
        XCTAssertTrue(choose.waitForExistence(timeout: 10))
        app.tap()
        XCTAssertTrue(app.staticTexts["Camera access is off"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Open Settings"].exists)
        let preview = app.descendants(matching: .any).matching(identifier: "camera.preview").firstMatch
        let shutter = app.buttons["camera.capture"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertTrue(shutter.exists)
        XCTAssertEqual(shutter.frame.midX, preview.frame.midX, accuracy: 2, "Shutter stays centered in the viewfinder.")
        XCTAssertTrue(preview.frame.contains(shutter.frame), "Shutter belongs inside the preview.")
        let flash = app.buttons["camera.flash"]
        let flip = app.buttons["camera.flip"]
        XCTAssertTrue(preview.frame.contains(flash.frame))
        XCTAssertTrue(preview.frame.contains(flip.frame))
        XCTAssertLessThan(flip.frame.maxX, shutter.frame.minX)
        XCTAssertGreaterThan(flash.frame.minX, shutter.frame.maxX)
        XCTAssertEqual(flash.frame.midY, shutter.frame.midY, accuracy: 2)
        XCTAssertEqual(flip.frame.midY, shutter.frame.midY, accuracy: 2)
        XCTAssertTrue(preview.frame.contains(app.buttons["camera.close"].frame))
        XCTAssertFalse(app.staticTexts["New scan"].exists, "Camera controls belong inside the viewfinder.")
        XCTAssertFalse(app.buttons["camera.zoom"].exists, "Zoom uses pinch and VoiceOver adjustment, without zoom buttons.")
        XCTAssertGreaterThan(preview.frame.height, app.frame.height * 0.65, "The preview uses the available height.")
        XCTAssertEqual(choose.frame.midX, preview.frame.midX, accuracy: 2, "Photo import is centered below the viewfinder.")
        XCTAssertGreaterThanOrEqual(choose.frame.minY, preview.frame.maxY)
        XCTAssertTrue(app.frame.contains(choose.frame), "The photo library footer stays on screen.")
        choose.tap()
        let closePicker = app.navigationBars.buttons["Cancel"]
        XCTAssertTrue(closePicker.waitForExistence(timeout: 10))
        closePicker.tap()
        let closeCamera = app.buttons["camera.close"]
        XCTAssertTrue(closeCamera.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: closeCamera)
        waitForExpectations(timeout: 5)
        closeCamera.tap()
        XCTAssertTrue(app.staticTexts["Start with a face scan"].waitForExistence(timeout: 5))
    }

    private func element(_ label: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func tap(_ label: String, in app: XCUIApplication) {
        let target = element(label, in: app)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Missing accessible element: \(label)")
        for _ in 0..<4 where !target.isHittable { app.swipeUp() }
        target.tap()
    }
}
