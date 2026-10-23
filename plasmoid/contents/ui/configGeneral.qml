import QtQuick
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kcmutils as KCM

KCM.SimpleKCM {
    id: page

    property alias cfg_serverUrl: serverUrl.text
    property alias cfg_token: token.text
    property string cfg_viewMode
    property alias cfg_dark: dark.checked
    property alias cfg_backgroundOpacity: opacity.value

    readonly property bool pl: Qt.locale().name.startsWith("pl")

    function t(en, pl) {
        return page.pl ? pl : en
    }

    Kirigami.FormLayout {
        QQC2.TextField {
            id: serverUrl
            Kirigami.FormData.label: page.t("Server address:", "Adres serwera:")
            placeholderText: "localhost:8787"
        }

        QQC2.TextField {
            id: token
            Kirigami.FormData.label: page.t("Token:", "Token:")
            echoMode: TextInput.PasswordEchoOnEdit
        }

        QQC2.ComboBox {
            Kirigami.FormData.label: page.t("Show:", "Pokazuj:")
            textRole: "text"
            valueRole: "value"
            model: [
                { text: page.t("Whole board", "Całą tablicę"), value: "fit" },
                { text: page.t("Same area as the tablet", "To samo co tablet"), value: "tablet" }
            ]
            Component.onCompleted: currentIndex = indexOfValue(page.cfg_viewMode)
            onActivated: page.cfg_viewMode = currentValue
        }

        QQC2.CheckBox {
            id: dark
            Kirigami.FormData.label: page.t("Look:", "Wygląd:")
            text: page.t("Dark board", "Ciemna tablica")
        }

        QQC2.Slider {
            id: opacity
            Kirigami.FormData.label: page.t("Background opacity:", "Krycie tła:")
            from: 0
            to: 100
            stepSize: 5
        }
    }
}
