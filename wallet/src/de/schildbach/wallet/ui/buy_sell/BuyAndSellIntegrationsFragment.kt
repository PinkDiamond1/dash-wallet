/*
 * Copyright 2021 Dash Core Group.
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.buy_sell

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.adapter.BuyAndSellDashServicesAdapter
import de.schildbach.wallet.data.ServiceType
import de.schildbach.wallet.ui.coinbase.CoinbaseActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentBuySellIntegrationsBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.dash.wallet.integration.uphold.ui.UpholdSplashActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class BuyAndSellIntegrationsFragment : Fragment(R.layout.fragment_buy_sell_integrations) {
    companion object {
        val log: Logger = LoggerFactory.getLogger(BuyAndSellIntegrationsFragment::class.java)
    }

    private val binding by viewBinding(FragmentBuySellIntegrationsBinding::bind)
    private val viewModel by viewModels<BuyAndSellViewModel>()
    private val buyAndSellDashServicesAdapter: BuyAndSellDashServicesAdapter by lazy {
        BuyAndSellDashServicesAdapter(viewModel.config.format.noCode()) { model ->
            when (model.serviceType) {
                ServiceType.UPHOLD -> onUpholdItemClicked()
                ServiceType.COINBASE -> onCoinbaseItemClicked()
            }
        }
    }

    private val coinbaseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Constants.RESULT_CODE_GO_HOME) {
            findNavController().popBackStack(R.id.walletFragment, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log.info("starting Buy and Sell Dash fragment")

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // check for missing keys from service.properties
        if (!viewModel.hasValidCredentials) {
            binding.keysMissingError.isVisible = true
        }

        binding.dashServicesList.itemAnimator = null
        binding.dashServicesList.adapter = buyAndSellDashServicesAdapter

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { isConnected ->
            binding.networkStatusStub.isVisible = !isConnected
        }

        viewModel.servicesList.observe(viewLifecycleOwner) {
            buyAndSellDashServicesAdapter.submitList(it)
        }

        lifecycleScope.launchWhenResumed {
            checkLiquidStatus()
        }
    }

    private fun onUpholdItemClicked() {
        viewModel.logEnterUphold()

        if (viewModel.isUpholdAuthenticated) {
            startActivity(Intent(requireContext(), UpholdAccountActivity::class.java))
        } else {
            startActivity(Intent(requireContext(), UpholdSplashActivity::class.java))
        }
    }

    private fun onCoinbaseItemClicked() {
        viewModel.logEnterCoinbase()

        if (viewModel.isCoinbaseAuthenticated) {
            coinbaseLauncher.launch(Intent(requireContext(), CoinbaseActivity::class.java))
        } else {
            safeNavigate(BuyAndSellIntegrationsFragmentDirections.buySellToOverview(ServiceType.COINBASE))
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.monitorNetworkStateChange()
        viewModel.updateBalances()
        viewModel.updateServicesStatus()
    }

    private fun checkLiquidStatus() {
        val liquidClient = LiquidClient.getInstance()

        if (liquidClient.isAuthenticated) {
            AdaptiveDialog.custom(
                R.layout.dialog_liquid_unavailable,
                null,
                "",
                "",
                "",
                getString(android.R.string.ok)
            ).apply { isCancelable = false }
             .show(requireActivity()) {
                 liquidClient.clearLiquidData()
            }
        }
    }
}
