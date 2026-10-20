import XCTest

final class ScanFaceUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testApplicationLaunchesAndExposesNavigation() {
        let app = XCUIApplication()
        app.launchArguments = ["--uitesting-reset"]
        app.launch()
        XCTAssertTrue(app.staticTexts["ScanFace"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Overview"].exists || app.buttons["Overview"].exists)
        XCTAssertTrue(app.staticTexts["History"].exists || app.buttons["History"].exists)
    }

    func testThemePersistsAndSampleHistoryOpensResult() {
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
        tap("Explore sample results", in: app)
        XCTAssertTrue(app.staticTexts["Latest scan"].waitForExistence(timeout: 5))
        tap("View", in: app)
        XCTAssertTrue(app.staticTexts["An illustrative result"].waitForExistence(timeout: 5))
        tap("Back", in: app)
        tap("History", in: app)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "saved scans")).firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Export scan history"].exists)
    }

    func testCameraCanOpenPickerAndReturnToOverview() {
        let app = XCUIApplication()
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
        choose.tap()
        let closePicker = app.navigationBars.buttons["Cancel"]
        XCTAssertTrue(closePicker.waitForExistence(timeout: 10))
        closePicker.tap()
        let closeCamera = app.buttons["camera.close"]
        XCTAssertTrue(closeCamera.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: closeCamera)
        waitForExpectations(timeout: 5)
        closeCamera.tap()
        XCTAssertTrue(app.staticTexts["Your face. Your perspective."].waitForExistence(timeout: 5))
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
