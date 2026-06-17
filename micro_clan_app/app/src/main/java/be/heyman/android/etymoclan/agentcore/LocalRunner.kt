package be.heyman.android.etymoclan.agentcore

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalInMemoryRunner(val agent: LocalAgent) {
    private val TAG = "LocalInMemoryRunner"
    private val chatHistory = mutableListOf<Pair<String, String>>() // Role to text

    fun getHistory(): List<Pair<String, String>> = chatHistory

    fun clearHistory() {
        chatHistory.clear()
    }

    suspend fun runAsync(newMessage: String): String = withContext(Dispatchers.Default) {
        chatHistory.add("user" to newMessage)
        val result = executeAgent(agent, newMessage)
        chatHistory.add("assistant" to result)
        result
    }

    private suspend fun executeAgent(currAgent: LocalAgent, input: String): String {
        return when (currAgent) {
            is LocalLlmAgent -> {
                val systemPrompt = currAgent.instruction.systemPrompt
                val userPrompt = input
                Log.d(TAG, "Executing LLM Agent: ${currAgent.name} using model: ${currAgent.model.name}")
                currAgent.model.generate(systemPrompt, userPrompt)
            }
            is LocalSequentialAgent -> {
                Log.d(TAG, "Executing Sequential Agent: ${currAgent.name} with ${currAgent.subAgents.size} sub-agents")
                var currentText = input
                for (subAgent in currAgent.subAgents) {
                    currentText = executeAgent(subAgent, currentText)
                }
                currentText
            }
            else -> "Agent type non supporté."
        }
    }
}
