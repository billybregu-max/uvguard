package com.uvguard.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.uvguard.app.data.PointDoseJour
import com.uvguard.app.data.Phototype
import com.uvguard.app.data.SessionExposition
import com.uvguard.app.data.UvRepository
import com.uvguard.app.data.construireCourbeDoseJour
import kotlinx.coroutines.launch
import java.io.File

/**
 * Écran plein écran présentant l'historique des jours passés (Mode précis) : liste avec le %
 * de dose de chaque jour, courbe de tendance sur les 30 derniers jours, suppression (un jour ou
 * tout l'historique), et export CSV.
 */
@Composable
fun EcranHistorique(
    repo: UvRepository,
    onFermer: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var historique by remember { mutableStateOf<List<UvRepository.EntreeHistorique>>(emptyList()) }
    var dialogueSuppressionTout by remember { mutableStateOf(false) }
    var chargementTermine by remember { mutableStateOf(false) }
    var dateSelectionnee by remember { mutableStateOf<String?>(null) }
    var sessionsDuJourSelectionne by remember { mutableStateOf<List<SessionExposition>>(emptyList()) }

    suspend fun recharger() {
        historique = repo.getHistorique()
        chargementTermine = true
    }

    LaunchedEffect(Unit) { recharger() }

    LaunchedEffect(dateSelectionnee) {
        val date = dateSelectionnee
        sessionsDuJourSelectionne = if (date != null) repo.getSessionsDuJour(date) else emptyList()
    }

    val dateAffichee = dateSelectionnee
    if (dateAffichee != null) {
        EcranDetailJour(
            date = dateAffichee,
            sessions = sessionsDuJourSelectionne,
            onRetour = { dateSelectionnee = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Barre du haut ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Historique", style = MaterialTheme.typography.headlineSmall)
            onFermer?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Filled.Close, contentDescription = "Fermer")
                }
            }
        }

        if (!chargementTermine) {
            Text("Chargement...", modifier = Modifier.padding(16.dp))
            return@Column
        }

        if (historique.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Aucune session enregistrée pour l'instant.", textAlign = TextAlign.Center)
            }
            return@Column
        }

        // --- Courbe de tendance (30 derniers jours) ---
        Text(
            "Tendance (30 derniers jours)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val trenteDerniersJours = historique.take(30).sortedBy { it.date }
        CourbeTendance(
            donnees = trenteDerniersJours,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(16.dp),
        )

        // --- Actions globales ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { exporterHistoriqueCsv(context, historique) }) {
                Text("Exporter en CSV")
            }
            OutlinedButton(onClick = { dialogueSuppressionTout = true }) {
                Text("Tout supprimer")
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- Liste des jours ---
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(historique) { entree ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dateSelectionnee = entree.date }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${entree.date} →", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${entree.dosePourcent.toInt()} % de la dose du jour — ${libellePhototypes(entree.phototypes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            repo.supprimerSessionsDuJour(entree.date)
                            recharger()
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Supprimer ce jour")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (dialogueSuppressionTout) {
        AlertDialog(
            onDismissRequest = { dialogueSuppressionTout = false },
            title = { Text("Supprimer tout l'historique ?") },
            text = { Text("Cette action est irréversible : toutes les sessions enregistrées seront effacées.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.supprimerToutHistorique()
                        recharger()
                        dialogueSuppressionTout = false
                    }
                }) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogueSuppressionTout = false }) { Text("Annuler") }
            },
        )
    }
}

/** Libellé court du ou des phototypes d'une journée : "Type III", ou "Types III, IV" si mixte. */
private fun libellePhototypes(phototypes: List<Phototype>): String {
    if (phototypes.isEmpty()) return "phototype inconnu"
    return if (phototypes.size == 1) {
        "Type ${phototypes.first().name}"
    } else {
        "Types ${phototypes.joinToString(", ") { it.name }}"
    }
}

/**
 * Détail d'un jour de l'historique : courbe de dose cumulée en paliers au fil des heures (une
 * montée par session), avec le phototype indiqué discrètement — répond à "quand la dose a-t-elle
 * été prise", pas seulement le total déjà visible dans la liste.
 */
@Composable
private fun EcranDetailJour(date: String, sessions: List<SessionExposition>, onRetour: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRetour) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
            }
            Text(date, style = MaterialTheme.typography.headlineSmall)
        }

        if (sessions.isEmpty()) {
            Text(
                "Aucune session détaillée disponible pour ce jour.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return
        }

        Text(
            "Dose cumulée dans la journée",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val points = remember(sessions) { construireCourbeDoseJour(sessions) }
        val phototypes = remember(sessions) { sessions.map { it.phototype }.distinct() }

        CourbeDoseJour(
            points = points,
            date = date,
            phototypes = phototypes,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        Text(
            "Chaque palier correspond à une session \"Je m'expose\" → \"Je suis à l'ombre\". " +
                "Total du jour : ${points.lastOrNull()?.dosePourcentCumule?.toInt() ?: 0} %.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CourbeDoseJour(
    points: List<PointDoseJour>,
    date: String,
    phototypes: List<Phototype>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val maxDose = (points.maxOf { it.dosePourcentCumule }).coerceAtLeast(10.0)

    val debutJourMillis = remember(date) {
        java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val finJourMillis = debutJourMillis + 24 * 3600 * 1000L

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            fun xPour(millis: Long): Float {
                val fraction = ((millis - debutJourMillis).toFloat() / (finJourMillis - debutJourMillis).toFloat())
                    .coerceIn(0f, 1f)
                return fraction * size.width
            }
            fun yPour(dose: Double): Float {
                return (size.height - (dose / maxDose * size.height)).toFloat()
            }

            for (i in 0 until points.size - 1) {
                drawLine(
                    color = Color(0xFF1565C0),
                    start = Offset(xPour(points[i].heureEpochMillis), yPour(points[i].dosePourcentCumule)),
                    end = Offset(xPour(points[i + 1].heureEpochMillis), yPour(points[i + 1].dosePourcentCumule)),
                    strokeWidth = 4f,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0h", "6h", "12h", "18h", "24h").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            libellePhototypes(phototypes),
            style = MaterialTheme.typography.labelSmall,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CourbeTendance(donnees: List<UvRepository.EntreeHistorique>, modifier: Modifier = Modifier) {
    if (donnees.isEmpty()) return
    val maxDose = (donnees.maxOf { it.dosePourcent }).coerceAtLeast(100.0)

    Canvas(modifier = modifier) {
        val largeurBarre = size.width / donnees.size
        donnees.forEachIndexed { index, entree ->
            val hauteurBarre = (entree.dosePourcent / maxDose * size.height).toFloat()
            val couleur = when {
                entree.dosePourcent < 50 -> Color(0xFF2E7D32)
                entree.dosePourcent < 80 -> Color(0xFFF9A825)
                entree.dosePourcent < 100 -> Color(0xFFEF6C00)
                else -> Color(0xFFD32F2F)
            }
            drawRect(
                color = couleur,
                topLeft = Offset(x = index * largeurBarre + largeurBarre * 0.15f, y = size.height - hauteurBarre),
                size = androidx.compose.ui.geometry.Size(width = largeurBarre * 0.7f, height = hauteurBarre),
            )
        }
    }
}

/** Génère un fichier CSV temporaire et ouvre le sélecteur de partage Android pour l'exporter. */
private fun exporterHistoriqueCsv(context: Context, historique: List<UvRepository.EntreeHistorique>) {
    val contenu = buildString {
        appendLine("Date,Dose (%),Phototype(s)")
        historique.sortedBy { it.date }.forEach { entree ->
            val phototypesTexte = entree.phototypes.joinToString("+") { it.name }
            appendLine("${entree.date},${"%.0f".format(entree.dosePourcent)},$phototypesTexte")
        }
    }

    val dossierExports = File(context.cacheDir, "exports").apply { mkdirs() }
    val fichier = File(dossierExports, "uvguard_historique.csv")
    fichier.writeText(contenu)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Exporter l'historique UV Guard"))
}
