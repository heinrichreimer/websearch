package de.unihalle.informatik.bigdata.knjigica.parser

import de.unihalle.informatik.bigdata.knjigica.data.Libretto
import de.unihalle.informatik.bigdata.knjigica.data.Plot
import de.unihalle.informatik.bigdata.knjigica.data.Role
import de.unihalle.informatik.bigdata.knjigica.util.languageRange
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*


object OperaLibLibrettoParser : Parser<File, Libretto> {
    override suspend fun parse(source: File): Libretto {
        val document = Jsoup.parse(source, Charsets.UTF_8.name())

        val languageScript = document.head()
                .select("script[type=\"text/javascript\"]")
                .last()
                .html()
        val languageScriptRegex: Regex = Regex("menuEUrid\\( ?'([a-z]{3})' ?\\) ?;")
        val languageString = languageScript.lineSequence()
                .map(String::trim)
                .firstOrNull { it matches languageScriptRegex }
                ?.replace(languageScriptRegex) { result ->
                    result.groups[1]?.value ?: ""
                }
                ?.takeIf(String::isNotBlank)
        val locale = when (languageString) {
            "fra" -> Locale.FRENCH
            "deu" -> Locale.GERMAN
            "eng" -> Locale.ENGLISH
            "rus" -> Locale.Builder().setLanguage("ru").setScript("Cyrl").build();
            else -> Locale.ITALIAN
        }
        println("language: ${locale.languageRange.range}")

        val title = document.select("h2")[0].text()
        println("title: $title")

        val opera = document.select("div#ps-tabella")

        val meta = opera.select("div.rid_opera")

        val ridInfos = document.select("p[class=\"rid_info\"]")
        val subtitle = ridInfos[0].wholeText().lineSequence().firstOrNull()
        println("subtitle: $subtitle")

        val ridInfoBolds = ridInfos[1].select(" > b")
        val textAuthor = ridInfoBolds[0].text()
        println("textAuthor: $textAuthor")
        val musicAuthor = ridInfoBolds[1].text()
        println("musicAuthor: $musicAuthor")

        // TODO "Das Rheingold" from "Richard Wagner" / "Mefistofele" from "Arrigo Boito" has both music and text author combined.
        val (premiereDateString, premiereLocation) = ridInfoBolds[2].text()
                .split(',')
                .map(String::trim)
        println("premiereDateString: $premiereDateString")
        println("premiereLocation: $premiereLocation")
        val format: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", locale)
        val premiereDate = try {
            format.parse(premiereDateString)
        } catch (e: DateTimeParseException) {
            null
        }
        println("premiereDate: ${premiereDate?.let(DateTimeFormatter.ISO_DATE::format)}")

        val rolesTable = meta
                .select("table")
                .first()
        val roles = rolesTable
                .select("tr")
                .filter { row ->
                    row.select("td.vtit").isEmpty()
                }
                .map { row ->
                    val descriptionColumn = row
                            .select("td.vdes > p")
                            .first()
                    val roleName = descriptionColumn
                            .select("b")
                            .first()
                            .text()
                    val roleDescription = descriptionColumn
                            .text()
                            .takeUnless { it == roleName }

                    val roleVoiceString = row
                            .select("td.vreg > p > i")
                            .first()
                            .text()
                            .toLowerCase()
                            .replace(Regex("[^a-zA-Z]+"), "")
                    val roleVoiceStringNormalized = roleVoiceString
                            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
                    val roleVoice = when (roleVoiceStringNormalized) {
                        "soprano", "sopran" -> Role.Voice.SOPRANO
                        "mezzosoprano", "mezzosopran" -> Role.Voice.MEZZO_SOPRANO
                        "alto", "alt", "altro" -> Role.Voice.ALTO
                        "contralto", "contraalto" -> Role.Voice.CONTRALTO
                        "tenor", "tenore" -> Role.Voice.TENOR
                        "baritone", "bariton" -> Role.Voice.BARITONE
                        "bass", "basso" -> Role.Voice.BASS
                        else -> when (roleVoiceString) {
                            "сопрано" -> Role.Voice.SOPRANO
                            "меццосопрано" -> Role.Voice.MEZZO_SOPRANO
                            "альт" -> Role.Voice.ALTO
                            "контральто" -> Role.Voice.CONTRALTO
                            "тенор" -> Role.Voice.TENOR
                            "баритон" -> Role.Voice.BARITONE
                            "бас" -> Role.Voice.BASS
                            else -> null
                        }
                    }
                    val role = Role(roleName, roleDescription, roleVoice)

                    if (roleVoice == null) println("!!!!!!!!!!!! ($roleVoiceString)") // TODO remove log.
                    println("role: $role")

                    role
                }

        val sideRoles = ridInfos[2]
                .textNodes()
                .joinToString(transform = TextNode::text)
                .split("\n\n")
                .first()
                .let {
                    // Drop heading (separated with `:`).
                    val colonIndex = it.indexOf(':')
                    it.drop(colonIndex + 1)
                }
                .split(';')
                .let {
                    // If side characters are not separated by semicolons or dots,
                    // try splitting by colons.
                    if (it.size == 1) it.first().split(',')
                    else it
                }
                .map { it.removeSuffix(".") }
                .map(String::trim)
                .filter(String::isNotBlank)
                .map {
                    val role = Role(it)
                    println("sideRole: $role")
                    role
                }

        val setting = ridInfos[2]
                .select("i")
                .text()
                .trim()
                .takeIf(String::isNotBlank)
        println("setting: $setting")

        val plotDivs = opera.select("> div")
                .dropWhile { !it.hasClass("rid_p_frg") }

        val allPlotDivs = plotDivs
                .filter { it.hasClass("rid_alltit") || it.hasClass("rid_alltxt") }
                .map(Element::text)

        println(allPlotDivs)

        plotDivs
                .mapNotNull {
                    when {
                        it.hasClass("rid_a") -> Plot.Section(it.text(), Plot.Section.Level.ACT)
                        it.hasClass("rid_s") -> Plot.Section(it.text(), Plot.Section.Level.SCENE)
                        it.hasClass("rid_p_num") -> Plot.Section(it.text(), Plot.Section.Level.NUMBER)
                        it.hasClass("rid_voce") -> null
                        it.hasClass("rid_indt") -> null
                        it.hasClass("rid_p_ind") -> null
                        it.hasClass("rid_p_scn") -> null
                        else -> null
                    }
                }
                .forEach {
                    println(it)
                }


        // = Aufzug
        //"rid_s" = Szenen
        //"rid_voce" = Rollenname
        //"rid_indt" = Instruktion
        //"rid_p_ind" = Aktion Schauspieler -> Klasse plot
        //"rid_p_scn" = Erzähler?


        TODO("Parse plot.")
    }
}