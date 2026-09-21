// SPDX-License-Identifier: GPL-3.0-only
/*
 *  Prism Launcher - Minecraft Launcher
 *  Copyright (C) 2022 Sefa Eyeoglu <contact@scrumplex.net>
 *  Copyright (C) 2023 TheKodeToad <TheKodeToad@proton.me>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, version 3.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 *      Copyright 2013-2021 MultiMC Contributors
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

#include "NewInstanceDialog.h"
#include "Application.h"
#include "ui/pages/modplatform/ModpackProviderBasePage.h"
#include "ui/pages/modplatform/import_ftb/ImportFTBPage.h"
#include "ui_NewInstanceDialog.h"

#include <BaseVersion.h>
#include <InstanceList.h>
#include <icons/IconList.h>
#include <tasks/Task.h>

#include "IconPickerDialog.h"
#include "VersionSelectDialog.h"
#include "ui/dialogs/CustomMessageBox.h"

#include <QButtonGroup>
#include <QDialogButtonBox>
#include <QToolButton>
#include <QFileDialog>
#include <QLayout>
#include <QPushButton>
#include <QScreen>
#include <QTimer>
#include <QValidator>
#include <utility>

#include "minecraft/PhiHud.h"
#include "ui/pages/modplatform/CustomPage.h"
#include "ui/pages/modplatform/ImportPage.h"
#include "ui/pages/modplatform/atlauncher/AtlPage.h"
#include "ui/pages/modplatform/flame/FlamePage.h"
#include "ui/pages/modplatform/ftb/FtbPage.h"
#include "ui/pages/modplatform/legacy_ftb/Page.h"
#include "ui/pages/modplatform/modrinth/ModrinthPage.h"
#include "ui/pages/modplatform/technic/TechnicPage.h"
#include "ui/widgets/PageContainer.h"

NewInstanceDialog::NewInstanceDialog(const QString& initialGroup,
                                     const QString& url,
                                     const QMap<QString, QString>& extraInfo,
                                     QWidget* parent)
    : QDialog(parent), ui(new Ui::NewInstanceDialog), m_instIconKey("default")
{
    ui->setupUi(this);

    ui->instNameTextBox->installEventFilter(this);

    refreshInstDirBox();

    auto lastUsedDir = APPLICATION->settings()->get("LastUsedInstDirForNewInstance").toString();
    int lastUsedIdx = ui->instDirBox->findData(lastUsedDir);
    ui->instDirBox->setCurrentIndex(lastUsedIdx >= 0 ? lastUsedIdx : 0);

    setWindowIcon(QIcon::fromTheme("new"));

    ui->iconButton->setIcon(APPLICATION->icons()->getIcon(m_instIconKey));

    QStringList groups = APPLICATION->instances()->getGroups();
    groups.prepend("");
    auto index = groups.indexOf(initialGroup);
    if (index == -1) {
        index = 1;
        groups.insert(index, initialGroup);
    }
    ui->groupBox->addItems(groups);
    ui->groupBox->setCurrentIndex(index);
    ui->groupBox->lineEdit()->setPlaceholderText(tr("No group"));

    // NOTE: m_buttons must be initialized before PageContainer, because it indirectly accesses m_buttons through setSuggestedPack! Do not
    // move this below.
    m_buttons = new QDialogButtonBox(QDialogButtonBox::Help | QDialogButtonBox::Ok | QDialogButtonBox::Cancel);

    m_container = new PageContainer(this, {}, this);
    m_container->useSidebarStyle(false);
    m_container->useCardStyle(true);
    m_container->useGridList(true);
    m_container->setBreadcrumbRoot(dialogTitle());
    m_container->setSizePolicy(QSizePolicy::Policy::Preferred, QSizePolicy::Policy::Expanding);
    m_container->layout()->setContentsMargins(0, 0, 0, 0);

    // step bar: three bevelled segments, the active one highlighted
    m_stepBar = new QWidget(this);
    auto* stepLayout = new QHBoxLayout(m_stepBar);
    stepLayout->setContentsMargins(0, 0, 0, 0);
    stepLayout->setSpacing(6);
    auto* stepGroup = new QButtonGroup(this);
    stepGroup->setExclusive(true);
    const QStringList stepTitles = { tr("1. Source"), tr("2. Version && loader"), tr("3. Name && icon") };
    for (int i = 0; i < stepTitles.size(); i++) {
        auto* b = new QToolButton(m_stepBar);
        b->setCheckable(true);
        b->setText(stepTitles[i]);
        b->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        b->setMinimumHeight(36);
        stepGroup->addButton(b, i);
        stepLayout->addWidget(b);
        m_stepButtons.append(b);
    }
    connect(stepGroup, &QButtonGroup::idClicked, this, &NewInstanceDialog::goToStep);

    ui->verticalLayout->insertWidget(0, m_stepBar);
    ui->verticalLayout->insertWidget(1, m_container);
    ui->verticalLayout->setStretchFactor(m_container, 1);
    ui->verticalLayout->insertStretch(3, 0);  // keeps the step-3 form at the top when the container is hidden
    // form (name / group / location / icon) is step 3; it comes from the .ui and is already in the layout

    m_buttons->setStandardButtons(QDialogButtonBox::Help | QDialogButtonBox::Cancel | QDialogButtonBox::Ok);
    m_backButton = new QPushButton(tr("< &Back"), this);
    m_nextButton = new QPushButton(tr("&Next >"), this);
    m_nextButton->setObjectName("launchButton");  // primary look in themes that style it
    m_buttons->addButton(m_backButton, QDialogButtonBox::ActionRole);
    m_buttons->addButton(m_nextButton, QDialogButtonBox::ActionRole);
    m_buttons->button(QDialogButtonBox::Ok)->hide();  // kept for pages that toggle it; mirrored onto Next/Create
    ui->verticalLayout->addWidget(m_buttons);

    connect(m_backButton, &QPushButton::clicked, this, [this] { goToStep(m_step - 1); });
    connect(m_nextButton, &QPushButton::clicked, this, [this] {
        if (m_step == 2)
            accept();
        else
            goToStep(m_step + 1);
    });
    connect(m_container, &PageContainer::pageActivated, this, [this] {
        if (m_step == 0)
            goToStep(1);
    });
    connect(m_container, &PageContainer::selectedPageChanged, this, [this](BasePage* /*previous*/, BasePage* /*selected*/) {
        m_buttons->button(QDialogButtonBox::Ok)->setEnabled(m_creationTask && !instName().isEmpty());
        if (m_step == 0)
            m_container->showListOnly();  // the stack re-shows the new page; keep step 1 list-only
        updateDialogState();
    });

    auto* okButton = m_buttons->button(QDialogButtonBox::Ok);
    okButton->setText(tr("OK"));
    connect(okButton, &QPushButton::clicked, this, &NewInstanceDialog::accept);

    auto* cancelButton = m_buttons->button(QDialogButtonBox::Cancel);
    cancelButton->setDefault(false);
    cancelButton->setAutoDefault(false);
    cancelButton->setText(tr("Cancel"));
    connect(cancelButton, &QPushButton::clicked, this, &NewInstanceDialog::reject);

    auto* helpButton = m_buttons->button(QDialogButtonBox::Help);
    helpButton->setDefault(false);
    helpButton->setAutoDefault(false);
    helpButton->setText(tr("Help"));
    connect(helpButton, &QPushButton::clicked, m_container, &PageContainer::help);

    goToStep(0);

    if (!url.isEmpty()) {
        QUrl actualUrl(url);
        m_container->selectPage("import");
        m_importPage->setUrl(url);
        m_importPage->setExtraInfo(extraInfo);
        goToStep(1);
    }

    updateDialogState();

    if (APPLICATION->settings()->get("NewInstanceGeometry").isValid()) {
        restoreGeometry(QByteArray::fromBase64(APPLICATION->settings()->get("NewInstanceGeometry").toString().toUtf8()));
    }
    // card list + drawer need room; older saved geometries are smaller than that
    auto geometry = this->screen()->availableSize();
    if (width() < 980 || height() < 620)
        resize(qMax(width(), 980), qMin(qMax(height(), 620), geometry.height() - 50));

    connect(m_container, &PageContainer::selectedPageChanged, this, &NewInstanceDialog::selectedPageChanged);
}

void NewInstanceDialog::reject()
{
    APPLICATION->settings()->set("NewInstanceGeometry", QString::fromUtf8(saveGeometry().toBase64()));

    // This is just so that the pages get the close() call and can react to it, if needed.
    m_container->prepareToClose();

    QDialog::reject();
}

void NewInstanceDialog::accept()
{
    if (!m_creationTask || instName().isEmpty())
        return;  // nothing to create yet (Enter on a step with no version picked)
    auto chosenDir = instDir();
    if (!QDir(chosenDir).exists()) {
        CustomMessageBox::selectable(
            this, tr("Directory unavailable"),
            tr("The instance directory \"%1\" is no longer accessible. Please choose another location.").arg(chosenDir),
            QMessageBox::Warning)
            ->exec();
        refreshInstDirBox();
        return;
    }

    APPLICATION->settings()->set("NewInstanceGeometry", QString::fromUtf8(saveGeometry().toBase64()));
    importIconNow();

    // This is just so that the pages get the close() call and can react to it, if needed.
    m_container->prepareToClose();

    QDialog::accept();
}

QList<BasePage*> NewInstanceDialog::getPages()
{
    QList<BasePage*> pages;

    m_importPage = new ImportPage(this);

    pages.append(new CustomPage(this));
    pages.append(m_importPage);
    pages.append(new AtlPage(this));
    if (APPLICATION->capabilities() & Application::SupportsFlame) {
        pages.append(new FlamePage(this));
    }
    pages.append(new FtbPage(this));
    pages.append(new LegacyFTB::Page(this));
    pages.append(new FTBImportAPP::ImportFTBPage(this));
    pages.append(new ModrinthPage(this));
    pages.append(new TechnicPage(this));

    return pages;
}

QString NewInstanceDialog::dialogTitle()
{
    return tr("New Instance");
}

NewInstanceDialog::~NewInstanceDialog()
{
    delete ui;
}

void NewInstanceDialog::refreshInstDirBox()
{
    QString previouslySelected = ui->instDirBox->currentData().toString();
    ui->instDirBox->clear();

    auto addAccessibleDir = [this](const QString& dir, const QString& label) {
        if (dir.isEmpty())
            return;
        QString canonical = QDir(dir).canonicalPath();
        if (canonical.isEmpty())
            return;
        ui->instDirBox->addItem(label.isEmpty() ? canonical : label, canonical);
    };

    auto instDir = APPLICATION->settings()->get("InstanceDir").toString();
    addAccessibleDir(instDir, tr("Default (%1)").arg(instDir));
    for (const auto& dir : APPLICATION->settings()->get("AdditionalInstanceDirs").toStringList()) {
        addAccessibleDir(dir, {});
    }

    int idx = ui->instDirBox->findData(previouslySelected);
    ui->instDirBox->setCurrentIndex(idx >= 0 ? idx : 0);
}

void NewInstanceDialog::setSuggestedPack(const QString& name, InstanceTask* task)
{
    m_creationTask.reset(task);

    m_suggestedName = name;

    if (!m_nameFieldEditedByUser) {
        ui->instNameTextBox->blockSignals(true);
        ui->instNameTextBox->setText(name);
        updateDialogState();
        ui->instNameTextBox->blockSignals(false);
        m_nameFieldSelectedOnce = false;
    }
    m_importVersion.clear();

    if (!task) {
        ui->iconButton->setIcon(APPLICATION->icons()->getIcon(m_instIconKey));
        m_importIcon = false;
    }

    auto allowOK = task != nullptr && !instName().isEmpty();
    m_buttons->button(QDialogButtonBox::Ok)->setEnabled(allowOK);
}

void NewInstanceDialog::setSuggestedPack(const QString& name, QString version, InstanceTask* task)
{
    m_creationTask.reset(task);

    m_suggestedName = name;

    if (!m_nameFieldEditedByUser) {
        ui->instNameTextBox->blockSignals(true);
        ui->instNameTextBox->setText(name);
        updateDialogState();
        ui->instNameTextBox->blockSignals(false);
        m_nameFieldSelectedOnce = false;
    }
    m_importVersion = std::move(version);

    if (!task) {
        ui->iconButton->setIcon(APPLICATION->icons()->getIcon(m_instIconKey));
        m_importIcon = false;
    }

    auto allowOK = task != nullptr && !instName().isEmpty();
    m_buttons->button(QDialogButtonBox::Ok)->setEnabled(allowOK);
}

void NewInstanceDialog::setSuggestedIconFromFile(const QString& path, const QString& name)
{
    m_importIcon = true;
    m_importIconPath = path;
    m_importIconName = name;

    // Hmm, for some reason they can be to small
    ui->iconButton->setIcon(QIcon(path));
}

void NewInstanceDialog::setSuggestedIcon(const QString& key)
{
    m_importIcon = false;

    if (key == "default") {
        ui->iconButton->setIcon(APPLICATION->icons()->getIcon(m_instIconKey));
        return;
    }

    auto icon = APPLICATION->icons()->getIcon(key);

    ui->iconButton->setIcon(icon);
}

InstanceTask* NewInstanceDialog::extractTask()
{
    InstanceTask* extracted = m_creationTask.release();
    if (!extracted)
        return nullptr;

    extracted->setName(ui->instNameTextBox->text().trimmed());
    extracted->setOriginalName(m_suggestedName.trimmed(), m_importVersion);

    extracted->setGroup(instGroup());
    extracted->setIcon(iconKey());
    extracted->setTargetDir(instDir());
    return extracted;
}

void NewInstanceDialog::updateDialogState()
{
    auto allowOK = m_creationTask && !instName().isEmpty();
    auto* okButton = m_buttons->button(QDialogButtonBox::Ok);
    if (okButton->isEnabled() != allowOK) {
        okButton->setEnabled(allowOK);
    }
    // wizard buttons (pages call this during construction, before the wizard chrome exists)
    if (!m_nextButton)
        return;
    m_backButton->setEnabled(m_step > 0);
    switch (m_step) {
        case 0:
            m_nextButton->setText(tr("&Next >"));
            m_nextButton->setEnabled(m_container->selectedPage() != nullptr);
            break;
        case 1:
            m_nextButton->setText(tr("&Next >"));
            m_nextButton->setEnabled(m_creationTask != nullptr);
            break;
        default:
            m_nextButton->setText(tr("&Create"));
            m_nextButton->setEnabled(allowOK);
            break;
    }
    m_nextButton->setDefault(true);
    // step subtitles
    if (auto* page = m_container->selectedPage())
        m_stepButtons[0]->setText(tr("1. Source") + "\n" + page->displayName());
    m_stepButtons[1]->setText(tr("2. Version && loader") + (m_suggestedName.isEmpty() ? QString() : "\n" + m_suggestedName));
    m_stepButtons[2]->setText(tr("3. Name && icon") + (instName().isEmpty() ? QString() : "\n" + instName()));
}

void NewInstanceDialog::goToStep(int step)
{
    m_step = qBound(0, step, 2);
    m_stepButtons[m_step]->setChecked(true);
    switch (m_step) {
        case 0:
            m_container->show();
            m_container->showListOnly();
            ui->formWidget->hide();
            break;
        case 1:
            m_container->show();
            m_container->showPageOnly();
            ui->formWidget->hide();
            break;
        default:
            m_container->hide();
            ui->formWidget->show();
            ui->installHudBox->setVisible(phiHudSupported());
            ui->instNameTextBox->setFocus();
            break;
    }
    updateDialogState();
}

bool NewInstanceDialog::phiHudSupported() const
{
    auto* custom = dynamic_cast<CustomPage*>(m_container->selectedPage());
    if (!custom || !custom->selectedVersion() || !custom->selectedLoaderVersion())
        return false;
    return PhiHud::supportFor(custom->selectedLoader(), custom->selectedVersion()->descriptor()).has_value();
}

bool NewInstanceDialog::installPhiHud() const
{
    return phiHudSupported() && ui->installHudBox->isChecked();
}

QString NewInstanceDialog::instName() const
{
    auto result = ui->instNameTextBox->text().trimmed();
    if (!result.isEmpty()) {
        return result;
    }
    result = m_suggestedName.trimmed();
    if (!result.isEmpty()) {
        return result;
    }
    return QString();
}

QString NewInstanceDialog::instGroup() const
{
    return ui->groupBox->currentText();
}
QString NewInstanceDialog::iconKey() const
{
    return m_instIconKey;
}

QString NewInstanceDialog::instDir() const
{
    return ui->instDirBox->currentData().toString();
}

void NewInstanceDialog::on_iconButton_clicked()
{
    importIconNow();  // so the user can switch back
    IconPickerDialog dlg(this);
    dlg.execWithSelection(m_instIconKey);

    if (dlg.result() == QDialog::Accepted) {
        m_instIconKey = dlg.selectedIconKey;
        ui->iconButton->setIcon(APPLICATION->icons()->getIcon(m_instIconKey));
        m_importIcon = false;
    }
}

void NewInstanceDialog::on_instNameTextBox_textChanged([[maybe_unused]] const QString& arg1)
{
    m_nameFieldEditedByUser = true;
    updateDialogState();
}

void NewInstanceDialog::importIconNow()
{
    if (m_importIcon) {
        APPLICATION->icons()->installIcon(m_importIconPath, m_importIconName);
        m_instIconKey = m_importIconName.mid(0, m_importIconName.lastIndexOf('.'));
        m_importIcon = false;
    }
    APPLICATION->settings()->set("NewInstanceGeometry", QString::fromUtf8(saveGeometry().toBase64()));
}

bool NewInstanceDialog::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == ui->instNameTextBox && event->type() == QEvent::FocusIn && !m_nameFieldSelectedOnce) {
        m_nameFieldSelectedOnce = true;
        QTimer::singleShot(0, ui->instNameTextBox, &QLineEdit::selectAll);
    }
    return QDialog::eventFilter(watched, event);
}

void NewInstanceDialog::selectedPageChanged(BasePage* previous, BasePage* selected)
{
    auto* prevPage = dynamic_cast<ModpackProviderBasePage*>(previous);
    if (prevPage) {
        m_searchTerm = prevPage->getSerachTerm();
    }

    auto* nextPage = dynamic_cast<ModpackProviderBasePage*>(selected);
    if (nextPage) {
        nextPage->setSearchTerm(m_searchTerm);
    }
}
