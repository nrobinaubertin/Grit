/*
 * Copyright (C) 2026  Niels Robin-Aubertin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shub39.grit.calendar

import com.shub39.grit.core.interfaces.SettingsDatastore
import com.shub39.grit.core.settings.Sections
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DayOfWeek

/** Fixed settings: weeks start on Monday, times use 24 hours. */
object FakeSettings : SettingsDatastore {
    override fun getStartOfTheWeekPref() = flowOf(DayOfWeek.MONDAY)

    override suspend fun setStartOfWeek(day: DayOfWeek) = Unit

    override fun getStartingSectionPref() = flowOf(Sections.Tasks)

    override suspend fun setStartingPage(page: Sections) = Unit

    override fun getIs24Hr() = flowOf(true)

    override suspend fun setIs24Hr(pref: Boolean) = Unit

    override fun getNotificationsFlow() = flowOf(false)

    override suspend fun setNotifications(pref: Boolean) = Unit

    override fun getBiometricLockPref() = flowOf(false)

    override suspend fun setBiometricPref(pref: Boolean) = Unit

    override fun getTaskReorderPref() = flowOf(false)

    override suspend fun setTaskReorderPref(pref: Boolean) = Unit

    override fun getCompactViewPref() = flowOf(false)

    override suspend fun setCompactView(pref: Boolean) = Unit
}
