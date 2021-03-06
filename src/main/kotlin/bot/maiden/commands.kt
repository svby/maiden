package bot.maiden

import bot.maiden.common.*
import bot.maiden.modules.Common.COMMAND_PARAMETER_PREDICATE
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.requests.restaction.MessageAction
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.jvmErasure

annotation class Command(
    val name: String = "",
    val hidden: Boolean = false,
)

annotation class HelpText(
    val description: String,
    val summary: String = "",
    val group: String = ""
)

val HelpText.displaySummary: String
    get() {
        return summary.takeIf { it.isNotBlank() }
            ?: description.split(Regex("\\.(?:\\s|$)")).firstOrNull()
            ?: description
    }

interface Module : AutoCloseable {
    suspend fun initialize(bot: Bot) = Unit
    override fun close() = Unit

    suspend fun onEvent(event: GenericEvent) = Unit
    suspend fun onMessage(message: Message): Boolean = true
}

enum class CommandSource {
    User,
    Scheduled,
    Other,
}

data class CommandContext(
    val source: CommandSource,

    val message: Message?,
    val requester: User?,
    val guild: Guild, // TODO guild should be nullable
    val channel: MessageChannel,

    val bot: Bot,

    val reply: (Message) -> MessageAction
) {
    val jda get() = bot.jda
    val modules get() = bot.modules
    val commands get() = bot.commands

    val database get() = bot.database

    suspend fun replyAsync(message: Message, transform: MessageAction.() -> Unit = {}): Message {
        return reply(message).apply(transform).await()
    }

    suspend fun replyAsync(text: String, transform: MessageAction.() -> Unit = {}) =
        replyAsync(MessageBuilder(text).build(), transform)

    suspend fun replyAsync(embed: MessageEmbed, transform: MessageAction.() -> Unit = {}) =
        replyAsync(MessageBuilder(embed).build(), transform)

    companion object {
        @JvmStatic
        fun fromMessage(message: Message, bot: Bot) = CommandContext(
            CommandSource.User,

            message,
            message.author,
            message.guild,
            message.channel,

            bot,

            { message.reply(it).mentionRepliedUser(false) }
        )

        @JvmStatic
        fun fromScheduled(requester: User?, channel: TextChannel, bot: Bot) = CommandContext(
            CommandSource.Scheduled,

            null,
            requester,
            channel.guild,
            channel,

            bot,

            { channel.sendMessage(it) }
        )
    }
}

suspend fun matchArgumentsOverload(
    functions: List<Bot.RegisteredCommand>,
    conversions: ConversionSet,
    args: List<Arg>
): List<Pair<Bot.RegisteredCommand, Map<KParameter, Pair<Any?, Int>>>> {
    val matches = mutableListOf<Pair<Bot.RegisteredCommand, Map<KParameter, Pair<Any?, Int>>>>()

    for (function in functions) {
        val match = matchArguments(function.function, conversions, args)
        match.getOrNull()?.let { matches.add(Pair(function, it)) }
    }

    return matches
}

suspend fun matchArguments(
    function: KFunction<*>,
    conversions: ConversionSet,
    args: List<Arg>
): Result<Map<KParameter, Pair<Any?, Int>>> {
    val results = mutableMapOf<KParameter, Pair<Any?, Int>>()

    val argIterator = args.iterator()
    var argIndex = 0

    val validParameters = function.parameters.filter(COMMAND_PARAMETER_PREDICATE)
    val validParameterCount = validParameters.size

    for ((index, parameter) in validParameters.withIndex()) {
        if (!COMMAND_PARAMETER_PREDICATE(parameter)) continue

        if (!argIterator.hasNext()) {
            if (index == validParameters.lastIndex && parameter.hasAnnotation<Optional>()) {
                // Optional parameter, ignore
                return Result.success(results)
            } else {
                return Result.failure(
                    Exception("Invalid parameter count; provided ${args.size}, expected $validParameterCount")
                )
            }
        }

        // TODO validate JoinRemaining parameter type (String)

        val arg = if (index == validParameters.lastIndex && validParameters.last().hasAnnotation<JoinRemaining>()) {
            argIterator.asSequence().reduce { acc, arg ->
                val newString = buildString {
                    append(acc.stringValue)
                    append(" ".repeat(arg.leadingSpaces))
                    append(arg.stringValue)
                }

                acc.copy(
                    stringValue = newString,
                    convertedValue = newString
                )
            }
        } else {
            argIterator.next().also {
                argIndex++
            }
        }

        val conversionList =
            conversions.getConverterList(arg.convertedValue::class, parameter.type.jvmErasure)
                ?: // TODO custom types
                return Result.failure(
                    Exception("Invalid arguments; could not convert '${arg.stringValue}' to the expected type ${parameter.type.jvmErasure}")
                )

        var value = arg.convertedValue
        for ((converter: ArgumentConverter<*, *>) in conversionList) {
            // TODO: proper error message
            @Suppress("UNCHECKED_CAST")
            value = (converter as ArgumentConverter<Any, Any>).convert(value).getOrThrow()
        }

        results[parameter] = Pair(value, conversionList.sumOf { it.second })
    }

    // Too many arguments provided
    if (argIterator.hasNext()) {
        return Result.failure(
            Exception("Invalid parameter count; provided ${args.size}, expected $validParameterCount")
        )
    }

    // TODO priority
    return Result.success(results)
}

suspend fun dispatch(
    conversions: ConversionSet,
    handlers: List<Bot.RegisteredCommand>,
    context: CommandContext,
    commandName: String,
    args: String
): Boolean {
    val handlersFiltered = handlers.filter { (_, function) -> function.name == commandName }
        .takeIf { it.isNotEmpty() }
        ?: run {
            context.replyAsync(
                commandNotFoundEmbed(context, handlers, commandName)
            )
            return false
        }

    // Argument conversion
    val parsedArgs = parseArguments(args)
    val converted = convertInitial(parsedArgs)

    // TODO: list/vararg parameter support

    val matches = matchArgumentsOverload(handlersFiltered, conversions, converted)
    val matchesByScore = matches.groupBy { it.second.entries.sumOf { it.value.second } }

    val bestMatchesEntry = matchesByScore.entries.maxOfWithOrNull(compareBy { it.key }) { it }
    val bestMatches = bestMatchesEntry?.value ?: emptyList()

    when {
        bestMatches.isEmpty() -> {
            context.replyAsync(noMatchingOverloadEmbed(context, handlersFiltered, commandName, converted))
            return false
        }
        bestMatches.size > 1 -> {
            context.replyAsync(
                multipleMatchingOverloadsEmbed(
                    context,
                    handlersFiltered,
                    commandName,
                    converted,
                    bestMatchesEntry?.key ?: -1
                )
            )
            return false
        }
        else -> {
            // Match found
            val match = bestMatches.single()

            val (command, matchedArguments) = match
            val invokeArguments = matchedArguments.toMutableMap()

            for (parameter in command.function.parameters) {
                if (parameter.kind == KParameter.Kind.INSTANCE || parameter.kind == KParameter.Kind.EXTENSION_RECEIVER)
                    invokeArguments[parameter] = Pair(command.receiver, 0)
                else if (parameter.type.jvmErasure == CommandContext::class)
                    invokeArguments[parameter] = Pair(context, 0)
            }

            command.function.callSuspendBy(invokeArguments.mapValues { it.value.first })

            return true
        }
    }
}
