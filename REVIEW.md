# Phi Launcher — revue du projet

## 1. Qu'est-ce que c'est

Phi Launcher est un launcher Minecraft dérivé de [Prism Launcher](https://github.com/PrismLauncher/PrismLauncher). Il garde tout le moteur de Prism (instances, comptes Microsoft, modpacks Modrinth/CurseForge/FTB/ATLauncher/Technic, Java automatique, updater) et ajoute ce qui en fait un produit distinct :

- des **statistiques de jeu** en direct (temps par jour, par instance, heatmap horaire, historique des sessions) ;
- une **identité propre** (nom, binaire, logo Φ, dossier de données, page de login, écran titre du jeu) ;
- une **interface repensée** : thème "Blocky" façon Minecraft, fenêtre principale en tableau de bord, paramètres en cartes, assistant de création d'instance en 3 étapes, police et couleurs modifiables ;
- **Phi HUD**, un mod overlay livré avec le launcher (21 widgets : FPS, TPS, coordonnées, inventaire, keystrokes, CPS, effets…), configurable depuis le launcher *et* depuis un menu en jeu où les widgets se déplacent à la souris.

Dépôt : `A:\claude\Iota`, branche `phi`, remote `origin` = https://github.com/Toutourina0110/PhiLauncher, remote `upstream` = Prism. Licence GPL-3.0 (héritée de Prism, copyrights Prism/PolyMC/MultiMC conservés).

## 2. Organisation du dépôt

```
A:\claude\Iota
├── launcher/              code C++/Qt du launcher (base Prism + ajouts Phi)
│   ├── StatsStore.*       historique des sessions (sessions.json)
│   ├── minecraft/PhiHud.* service Phi HUD côté launcher (support, install, config)
│   ├── ui/widgets/        ResumePanel, ActivityPanel, StatsCharts, PageContainer (cartes/tuiles), AppearanceWidget
│   ├── ui/dialogs/        StatsDialog, NewInstanceDialog (assistant)
│   ├── ui/instanceview/   InstanceCardDelegate (cartes d'instances)
│   ├── ui/pages/instance/ PhiHudPage (page HUD d'une instance)
│   ├── ui/themes/         ThemeManager, CustomTheme (police + palette depuis theme.json)
│   └── resources/themes/blocky/   thème par défaut (theme.json, themeStyle.css, README.txt)
├── mods/
│   ├── PHIHUD_SPEC.md     contrat launcher ⇄ mod (config JSON, widgets, menu)
│   ├── phihud-fabric/     mod Fabric, 1 source → 6 jars (1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3)
│   └── phihud-forge-1.8.9/ mod Forge 1.8.9
├── program_info/          branding (icônes, .rc, manifest, metainfo, Minecraft.ttf)
├── tests/                 tests QTest (dont StatsStore_test.cpp)
├── handoff.md             état courant / prochaines étapes pour reprendre le travail
└── REVIEW.md              ce document
```

Le code Prism d'origine est modifié le moins possible : les ajouts sont des fichiers nouveaux branchés en quelques points (constructeur de `MainWindow`, `Application.cpp`, `BaseInstance::setMinecraftRunning`, `PageContainer`). Cela garde la fusion avec `upstream/develop` faisable.

## 3. Le launcher

### 3.1 Statistiques

`StatsStore` journalise chaque partie dans `<data>/sessions.json` (`instance`, `name`, `start` en ms epoch, `duration` en secondes). La session est ouverte au lancement du jeu, mise à jour toutes les 30 s (et sauvegardée à chaque tick, écriture atomique via `QSaveFile`) et fermée à la sortie. Le temps de jeu déjà accumulé par Prism est importé une fois comme session "historique" sans date.

Consommateurs : la colonne **Activity** de la fenêtre principale (total, lancements, semaine, graphe 7 jours), le panneau **Resume** ("Playing for …" en direct), le dialog **Stats** (barres par jour avec sélecteur 7/30/90 j/tout, top instances, heatmap jour × heure, tableau des sessions) et un bouton de rafraîchissement manuel. Les graphes sont des `QWidget` peints avec `QPainter` à partir de la palette, donc corrects dans n'importe quel thème.

### 3.2 Identité

Tout passe par `program_info/CMakeLists.txt` : `Launcher_CommonName = PhiLauncher`, `DisplayName = Phi Launcher`, `AppID = org.philauncher.PhiLauncher`, binaire `philauncher.exe`, config `philauncher.cfg`, dossier `%APPDATA%\PhiLauncher`, variable `PHILAUNCHER_DATA_DIR`. Le logo Φ est décliné en ICO/ICNS/PNG/SVG. Au premier lancement, le launcher propose d'importer (copie) les données d'une installation Prism existante.

Les URL communautaires de Prism (Discord, Matrix, Reddit, bug tracker, flux d'actualités) sont vides par défaut, ce qui masque les boutons correspondants ; l'updater pointe sur le dépôt GitHub de Phi. Le client ID Microsoft reste celui de Prism (voir §6).

La page affichée après le login navigateur est servie localement (fond sombre, Φ, police Minecraft) au lieu de rediriger vers prismlauncher.org.

### 3.3 Thème et apparence

Le thème **Blocky** reproduit l'interface de Minecraft : boutons biseautés, zéro arrondi, police Minecraft 8 pt. Il est livré dans les ressources puis copié au premier démarrage dans `<data>/themes/blocky/` pour rester éditable :

- `theme.json` : palette (`Window`, `Base`, `Button`, `Highlight`, `Light/Mid/Dark`…) et police (`font.family`, `font.pointSize`) ;
- `themeStyle.css` : formes et bordures, toutes les couleurs via `palette(...)`.

Les thèmes d'origine de Prism (system/dark/bright/styles Qt) ne sont plus proposés. Dans **Paramètres → Apparence → Phi**, l'utilisateur choisit la police et modifie chaque couleur (pastille + hexa), avec trois presets (Blocky, Phi purple, Light) et un reset ; chaque changement réécrit `theme.json` et réapplique le thème sans redémarrer (`ThemeManager::reloadTheme`).

### 3.4 Interface

- **Fenêtre principale** : panneau *Resume* (instance sélectionnée ou dernière jouée : icône, version · loader, temps, gros bouton **Launch**, actions), grille de **cartes d'instances** (icône, nom, version · loader · temps, ▶ si en cours), colonne *Activity*. La barre d'outils d'instance de Prism est masquée mais ses actions restent disponibles (menu contextuel, raccourcis).
- **Paramètres** : liste de catégories en cartes (icône + titre + description), fil d'Ariane `Settings › Général`.
- **Nouvelle instance** : assistant en 3 étapes — *Source* (9 tuiles plein écran : Personnalisé, Importer, ATLauncher, CurseForge, FTB…), *Version & loader* (page du fournisseur), *Name & icon* (formulaire + case "Install Phi HUD").
- Icônes ajoutées dans les 4 thèmes d'icônes (activity, dashboard, group, play-big, import, custom, stats).

### 3.5 Côté launcher de Phi HUD

`launcher/minecraft/PhiHud.*` détecte le loader et la version d'une instance, choisit le jar (`phihud-fabric-<mc>.jar` / `phihud-forge-1.8.9.jar`, cherchés dans `<launcher>/phihud/`), l'installe dans `mods/` (avec téléchargement de Fabric API depuis Modrinth si absent), et lit/écrit `config/phihud.json` en lecture-modification-écriture (les clés qu'il ne connaît pas sont conservées). La page **HUD** d'une instance montre l'état d'installation et les réglages ; l'assistant installe le mod à la création si la case est cochée.

## 4. Le mod Phi HUD

### 4.1 Cibles et build

| Loader | Versions | Toolchain |
|---|---|---|
| Fabric | 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | Loom 1.17, JDK 21 (Gradle provisionne JDK 25 pour compiler 26.x), mappings Mojang, Fabric API |
| Forge | 1.8.9 | ForgeGradle 2.1, JDK 8, mappings stable_22 |

Le mod Fabric a un seul arbre de sources ; les différences d'API entre versions sont isolées dans une classe `Compat` par famille (`src/v1_21_11`, `src/v26_1`, `src/v26`). `gradlew build -Pmc=<version>` produit le jar de cette version. Les jars sont copiés à côté du launcher par la cible CMake `phihud_jars`.

### 4.2 Fonctionnalités

- **21 widgets** : fps, tps (estimé côté client depuis les paquets d'heure), coords, direction, ping, memory, clock, armor (icônes + durabilité), inventory (grille 9×3), keystrokes (mini clavier), cps, speed, biome, gametime, light, target, server, session, effects, hunger, health.
- **Positionnement** : ancrage par coin avec empilement (mode v1) ou position libre `x`/`y` en fractions d'écran avec pivot automatique, stable quel que soit la résolution ou l'échelle GUI.
- **Menu en jeu** (touche H, boutons "Phi HUD" sur l'écran titre et le menu pause) : liste des widgets avec interrupteurs ON/OFF et recherche, vue *Settings* (activation globale, échelle, couleur, fond + opacité, ombre, marge), réglages par widget (échelle, couleur, fond — héritent du global si non définis), aperçu en direct avec glisser-déposer et aimantation, *Reset layout* / *Done*, Échap = sauvegarde.
- **Écran titre** : logo Φ 128×128 à la place du logo Minecraft, splash "Powered by Phi Launcher", badge "Phi Launcher".
- **Config** `config/phihud.json` (`"version": 2`) rechargée à chaud toutes les 2 s. Le mod ne touche que ses propres clés, le launcher que les siennes : aucun des deux n'écrase l'autre.
- **Performance** : texte des widgets recalculé toutes les 2 ticks (1 tick pour fps/cps/speed), layout recalculé seulement sur changement de config, redimensionnement ou changement de largeur de texte, aucune allocation par frame, un seul `lastModified()` par poll.

Le contrat complet (schéma JSON, table des widgets, comportement du menu) est dans [mods/PHIHUD_SPEC.md](mods/PHIHUD_SPEC.md).

## 5. Build et outils

- **Launcher** : CMake ≥ 3.28, MSVC 2022, Qt 6.9.3 (`A:\Qt`), vcpkg pour cmark/libarchive/qrencode/toml++/zlib, JDK 17 pour l'assistant Java de Prism. `cmake --preset windows_msvc -DCMAKE_PREFIX_PATH=A:/Qt/6.9.3/msvc2022_64 -DENABLE_LTO=OFF` puis `cmake --build build --config Debug`. Les warnings sont des erreurs (`/W4 /WX`).
- **Exécutable** : `build/Debug/philauncher.exe` (DLL Qt déployées avec `windeployqt`).
- **Tests** : `ctest -C Debug` dans `build/` (StatsStore couvre enregistrement, agrégation, sessions live, sauvegarde atomique).
- **Mods** : voir §4.1 ; jars dans `mods/*/build/libs/`.

## 6. Limites et points d'attention

- **Rendu en jeu non vérifié** : le mod est vérifié à la compilation et au chargement (`[phihud] Phi HUD loaded` dans le log), mais le rendu des widgets, du menu et de l'écran titre n'a été observé que pour les premières versions. À tester en jeu.
- **Client ID Microsoft** : celui de Prism. Un client ID propre exige l'approbation de Mojang (formulaire fermé aux comptes personnels au moment de l'écriture), sans quoi `api.minecraftservices.com` renvoie 403.
- **Langue** : les nouvelles chaînes sont en anglais dans `tr()` ; pas de traduction française fournie.
- **Backend Prism** conservé volontairement : métadonnées (`meta.prismlauncher.org`), traductions, wiki d'aide.
- **Build Release / installeur** : seul le build Debug a été produit.
- **Synchronisation avec Prism** : `git fetch upstream && git merge upstream/develop` manuel ; un workflow GitHub Actions (merge automatique + release) reste à écrire pour que le bouton Update serve à quelque chose.

## 7. Historique

21 commits sur la branche `phi` au-dessus de Prism `43a67faef`, dans l'ordre : statistiques → rebrand (Iota puis Phi) → fix login + page locale → thème Blocky → tableau de bord, cartes, assistant → sessions live → Phi HUD v1 (overlay), v2 (éditeur, écran titre), v3 (menu, overrides, fix logo), v4 (12 widgets, perf) → éditeur police/couleurs, retrait des thèmes Prism, optimisations, tests. Détail commit par commit dans [handoff.md](handoff.md).
