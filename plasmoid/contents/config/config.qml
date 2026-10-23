import QtQuick
import org.kde.plasma.configuration

ConfigModel {
    ConfigCategory {
        name: Qt.locale().name.startsWith("pl") ? "Ogólne" : "General"
        icon: "configure"
        source: "configGeneral.qml"
    }
}
