/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.brokensite

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.brokensite.api.BrokenSiteSender
import com.duckduckgo.app.brokensite.model.BrokenSite
import com.duckduckgo.app.brokensite.model.BrokenSiteCategory
import com.duckduckgo.app.brokensite.model.BrokenSiteCategory.*
import com.duckduckgo.app.global.SingleLiveEvent
import com.duckduckgo.app.global.isMobileSite
import com.duckduckgo.app.pixels.AppPixelName
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.privacy.config.api.AmpLinks
import javax.inject.Inject

@ContributesViewModel(ActivityScope::class)
class BrokenSiteViewModel @Inject constructor(
    private val pixel: Pixel,
    private val brokenSiteSender: BrokenSiteSender,
    private val ampLinks: AmpLinks,
) : ViewModel() {

    data class ViewState(
        val indexSelected: Int = -1,
        val categorySelected: BrokenSiteCategory? = null,
        val submitAllowed: Boolean = false,
    )

    sealed class Command {
        object ConfirmAndFinish : Command()
    }

    val viewState: MutableLiveData<ViewState> = MutableLiveData()
    val command: SingleLiveEvent<Command> = SingleLiveEvent()
    var indexSelected = -1
    val categories: List<BrokenSiteCategory> = listOf(
        ImagesCategory,
        PaywallCategory,
        CommentsCategory,
        VideosCategory,
        LinksCategory,
        ContentCategory,
        LoginCategory,
        UnsupportedCategory,
        OtherCategory,
    )
    private var blockedTrackers: String = ""
    private var surrogates: String = ""
    private var url: String = ""
    private var upgradedHttps: Boolean = false
    private val viewValue: ViewState get() = viewState.value!!
    private var urlParametersRemoved: Boolean = false
    private var consentManaged: Boolean = false
    private var consentOptOutFailed: Boolean = false
    private var consentSelfTestFailed: Boolean = false

    init {
        viewState.value = ViewState()
    }

    fun setInitialBrokenSite(
        url: String,
        blockedTrackers: String,
        surrogates: String,
        upgradedHttps: Boolean,
        urlParametersRemoved: Boolean,
        consentManaged: Boolean,
        consentOptOutFailed: Boolean,
        consentSelfTestFailed: Boolean,
    ) {
        this.url = url
        this.blockedTrackers = blockedTrackers
        this.upgradedHttps = upgradedHttps
        this.surrogates = surrogates
        this.urlParametersRemoved = urlParametersRemoved
        this.consentManaged = consentManaged
        this.consentOptOutFailed = consentOptOutFailed
        this.consentSelfTestFailed = consentSelfTestFailed
    }

    fun onCategoryIndexChanged(newIndex: Int) {
        indexSelected = newIndex
    }

    fun onCategorySelectionCancelled() {
        indexSelected = viewState.value?.indexSelected ?: -1
    }

    fun onCategoryAccepted() {
        viewState.value = viewState.value?.copy(
            indexSelected = indexSelected,
            categorySelected = categories.elementAtOrNull(indexSelected),
            submitAllowed = canSubmit(),
        )
    }

    fun onSubmitPressed(webViewVersion: String, description: String?) {
        if (url.isNotEmpty()) {
            val lastAmpLinkInfo = ampLinks.lastAmpLinkInfo

            val brokenSite = if (lastAmpLinkInfo?.destinationUrl == url) {
                getBrokenSite(lastAmpLinkInfo.ampLink, webViewVersion, description)
            } else {
                getBrokenSite(url, webViewVersion, description)
            }

            brokenSiteSender.submitBrokenSiteFeedback(brokenSite)
            pixel.fire(
                AppPixelName.BROKEN_SITE_REPORTED,
                mapOf(Pixel.PixelParameter.URL to brokenSite.siteUrl),
            )
        }
        command.value = Command.ConfirmAndFinish
    }

    @VisibleForTesting
    fun getBrokenSite(
        urlString: String,
        webViewVersion: String,
        description: String?,
    ): BrokenSite {
        val category = categories[viewValue.indexSelected]
        return BrokenSite(
            category = category.key,
            description = description,
            siteUrl = urlString,
            upgradeHttps = upgradedHttps,
            blockedTrackers = blockedTrackers,
            surrogates = surrogates,
            webViewVersion = webViewVersion,
            siteType = if (Uri.parse(url).isMobileSite) MOBILE_SITE else DESKTOP_SITE,
            urlParametersRemoved = urlParametersRemoved,
            consentManaged = consentManaged,
            consentOptOutFailed = consentOptOutFailed,
            consentSelfTestFailed = consentSelfTestFailed,
        )
    }

    private fun canSubmit(): Boolean = categories.elementAtOrNull(indexSelected) != null

    companion object {
        const val MOBILE_SITE = "mobile"
        const val DESKTOP_SITE = "desktop"
    }
}
