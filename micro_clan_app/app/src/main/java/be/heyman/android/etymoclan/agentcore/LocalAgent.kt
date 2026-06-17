package be.heyman.android.etymoclan.agentcore

data class Instruction(val systemPrompt: String)

abstract class LocalAgent(
    val name: String,
    val description: String
)

class LocalLlmAgent(
    name: String,
    description: String,
    val model: LocalModel,
    val instruction: Instruction,
    val tools: List<String> = emptyList()
) : LocalAgent(name, description)

class LocalSequentialAgent(
    name: String,
    description: String,
    val subAgents: List<LocalAgent>
) : LocalAgent(name, description)
