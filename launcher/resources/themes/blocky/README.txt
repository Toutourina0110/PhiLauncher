Blocky theme for Phi Launcher
=============================

Colors live in theme.json. Edit them and restart the launcher (or switch
theme back and forth in Settings > Appearance).

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
    Light / Dark    bevel edges of buttons and panels (light = top-left, dark = bottom-right)
    Mid             secondary text (captions, versions, descriptions) and disabled borders
    Midlight        hover background of buttons

Shapes, borders and the font are in themeStyle.css. Fonts, images or other
files you reference from the css go in the resources/ folder.

To go back to the stock look, delete this folder: the launcher recreates it
on the next start.
