package id.fahrul.sanel

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.DynamicColors
import id.fahrul.sanel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var historyAdapter: ChatHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsManager.init(this)
        ConversationManager.init(this)

        ConversationManager.onDataChanged = {
            runOnUiThread { refreshHistory() }
        }

        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.nav_chat, R.string.nav_settings
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) {
                refreshHistory()
            }
        })

        navController.addOnDestinationChangedListener { _, dest, _ ->
            binding.toolbar.title = dest.label
        }

        // Chat history adapter
        historyAdapter = ChatHistoryAdapter(
            onItemClick = { conv -> loadConversation(conv) },
            onItemLongClick = { conv -> deleteConversation(conv) }
        )
        binding.rvChatHistory.layoutManager = LinearLayoutManager(this)
        binding.rvChatHistory.adapter = historyAdapter

        // New Chat button
        binding.btnNewChat.setOnClickListener { newChat() }

        // Settings at bottom
        binding.drawerSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            navController.navigate(R.id.settings_fragment)
        }
    }

    private fun refreshHistory() {
        historyAdapter.submitList(ConversationManager.getAll())
    }

    private fun newChat() {
        // Save current conversation first
        val chatFragment = supportFragmentManager.fragments.firstOrNull { it is ChatFragment } as? ChatFragment
        chatFragment?.saveCurrentConversation()

        // Clear the chat and start a fresh conversation
        ConversationManager.resetCurrentConversation()
        chatFragment?.clearChat()

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        if (!navController.popBackStack(R.id.chat_fragment, false)) {
            navController.navigate(R.id.chat_fragment)
        }
    }

    private fun loadConversation(conv: Conversation) {
        ConversationManager.setPendingLoad(conv.id)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        navController.navigate(R.id.chat_fragment)
    }

    private fun deleteConversation(conv: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Delete chat")
            .setMessage("Are you sure you want to delete \"${conv.title}\"?")
            .setPositiveButton("Delete") { _: DialogInterface, _: Int ->
                val isCurrent = conv.id == ConversationManager.getCurrentId()
                ConversationManager.delete(conv.id)
                refreshHistory()
                if (isCurrent) {
                    // Current chat was deleted — start a fresh one
                    val chatFragment = supportFragmentManager.fragments.firstOrNull { it is ChatFragment } as? ChatFragment
                    chatFragment?.clearChat()
                    if (!navController.popBackStack(R.id.chat_fragment, false)) {
                        navController.navigate(R.id.chat_fragment)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
