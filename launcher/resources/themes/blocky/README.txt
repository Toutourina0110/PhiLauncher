Blocky theme for Phi Launcher
=============================

The easiest way to edit this theme is Settings > Appearance > Phi: pick the
UI font and size, click a color swatch (or type a hex value) and the launcher
updates live. The Presets buttons fill in a whole palette at once, and
"Reset to defaults" restores the bundled copy of this folder. Everything on
that page is written back to theme.json, so hand-editing works too: edit the
file and press "Reload All" in Settings > Appearance (or restart).

Colors live under "colors" in theme.json:

    Window          background of windows and panels
    WindowText      text on windows
    Base            background of lists, text fields, instance grid
    AlternateBase   alternate row color in lists
    Text            text inside lists and fields
    Button          buttons; the bevel is derived automatically (lighter top-left, darker bottom-right)
    ButtonText      text on buttons
    Highlight       selection, launch button, checked toggles, progress
    HighlightedText text on highlighted items
    Link            hyperlinks
    ToolTipBase / ToolTipText   tooltips
    BrightText      errors / warnings accent
    PlaceholderText placeholder text in empty fields
    Light / Dark    bevel edges of buttons and panels (light = top-left, dark = bottom-right)
    Mid             secondary text (captions, versions, descriptions) and disabled borders
    Midlight        hover background of buttons

The UI font is the optional "font" block:

    "font": { "family": "Minecraft", "pointSize": 8 }

Leave it out to use the system font. The Minecraft font ships with the
launcher; any other family installed on the system works as well.

Shapes and borders are in themeStyle.css. Fonts, images or other files you
reference from the css go in the resources/ folder.

To go back to the stock look, use "Reset to defaults" in Settings > Appearance
or delete this folder: the launcher recreates it on the next start.
