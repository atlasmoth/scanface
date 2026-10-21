import XCTest
@testable import ScanFace

final class LaunchHandoffTests: XCTestCase {
    func testPersistedAppearanceOverridesOppositeSystemTheme() {
        let dark = LaunchAppearance.decode(Data(#"{"theme":"DARK"}"#.utf8))
        XCTAssertTrue(dark.isDark(systemDark: false))
        let light = LaunchAppearance.decode(Data(#"{"theme":"LIGHT"}"#.utf8))
        XCTAssertFalse(light.isDark(systemDark: true))
    }

    func testMissingOrUnreadablePreferenceUsesActualSystemAppearance() {
        for data in [nil, Data(), Data(#"{"theme":"UNKNOWN"}"#.utf8), Data(#"{"records":[]}"#.utf8)] {
            let appearance = LaunchAppearance.decode(data)
            XCTAssertTrue(appearance.isDark(systemDark: true))
            XCTAssertFalse(appearance.isDark(systemDark: false))
        }
    }

    func testHandoffIsClaimedOnlyOnceAcrossHostRecreation() {
        let process = LaunchHandoffGate()
        XCTAssertTrue(process.claim())
        XCTAssertFalse(process.claim())
        XCTAssertFalse(process.claim())
        XCTAssertTrue(LaunchHandoffGate().claim(), "A new process gets its own launch.")
    }
}
