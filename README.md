# Mon Panier V2.1 — projet Android

## Fonctionnalités du projet
- Application Android native Kotlin / Jetpack Compose.
- Scanner de ticket par caméra et OCR ML Kit.
- Écran de vérification/correction avant sauvegarde.
- Base de données locale Room.
- Historique, statistiques, paramètres français/arabe, DZD/CAD, thème clair/Dark.
- Export Excel `.xlsx`.
- Suivi de prix par produit à partir des lignes de tickets enregistrées, avec historique date/prix et courbe.
- Panier intelligent : comparaison des références exactes et suggestion heuristique de produits équivalents.

## Limites fonctionnelles importantes
- L'OCR et le parseur sont heuristiques : vérifier chaque ticket avant validation.
- Le suivi de prix utilise les tickets déjà enregistrés.
- Aucun accès aux prix en direct des enseignes n'est intégré. Les recommandations ne garantissent donc pas le prix actuellement affiché en magasin.
- La détection des produits équivalents est une première heuristique par mots-clés (famille de produit), pas une reconnaissance fiable de marque, volume, composition ou code-barres. Vérifier les caractéristiques avant achat.
- DZD et CAD sont des monnaies distinctes : aucune conversion automatique n'est effectuée.
- L'arabe est disponible sur les libellés principaux; une revue complète des traductions et de la disposition RTL reste nécessaire.
