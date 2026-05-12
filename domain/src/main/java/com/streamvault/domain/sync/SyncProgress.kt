package com.streamvault.domain.sync

/**
 * Snapshot immuable de la progression d'un cycle de synchronisation catalogue.
 *
 * Émis par `SyncManager` (côté `:data`) sur le bus partagé, consommé par l'UI Welcome.
 * Tous les champs sont des primitives ou des enums — la donnée est sûre à snapshotter
 * dans un `StateFlow`.
 *
 * @property section Section catalogue en cours d'indexation.
 * @property current Index de la fenêtre courante (catégorie, lot ou item selon la section).
 * @property total Total cible pour la section courante. `0` signale un mode indéterminé
 *                 (ex. profile HIGH pour LIVE, ou import M3U avant fin du parsing).
 * @property currentLabel Libellé contextuel de la fenêtre courante (nom de catégorie,
 *                        d'étape M3U, etc.). Peut être vide.
 * @property itemsIndexed Compteur cumulatif d'items importés depuis le début du sync,
 *                        toutes sections confondues.
 */
data class SyncProgress(
    val section: Section,
    val current: Int,
    val total: Int,
    val currentLabel: String,
    val itemsIndexed: Int
)

/**
 * Section du catalogue en cours de synchronisation. L'ordre déclaré reflète la séquence
 * d'exécution du sync (LIVE puis VOD puis SERIES).
 */
enum class Section {
    LIVE,
    VOD,
    SERIES
}
