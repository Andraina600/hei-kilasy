# Journal IA — C1 : pénalité de retard

Outil utilisé : Claude Code (Claude Opus 5.5).

---

## Échange 1 — Analyse de la demande avant de coder

**Demande** : analyser la consigne C1 avec l'IA avant toute modification, et avoir son avis.

**Proposition** : l'IA a lu tout le code et relevé que la note sur 20 était calculée à **4 endroits**, avec **3 arrondis différents** :

| Endroit | Fichier | Calcul avant C1 |
|---|---|---|
| Correction (réponse + email) | `SubmissionController.grade()` | `Math.round(x * 10) / 10.0` sur des `double` |
| Consultation d'une soumission | `SubmissionController.get()` | idem (dupliqué) |
| Relevé de notes | `GradeReport.build()` | `BigDecimal`, HALF_UP, 1 décimale |
| Export CSV | `CsvExport.export()` | `Math.floor` → **troncature** |

Elle a aussi souligné que le critère « même note partout » n'était **déjà pas respecté avant C1**, à cause du `floor` du CSV. Avec la pénalité ce serait pire : 15,5 × 0,9 = 13,95 donne 13,9 en floor et 14,0 en HALF_UP. En `double`, 13,95 n'est pas représentable exactement, donc `Math.round` peut lui aussi donner 13,9.

Elle a remarqué que les données de démo correspondent exactement aux exemples de la consigne :
- Bob, rendu le 11/11 à 02h → 1 jour → 14,4
- Clara, rendu le 13/11 → 3 jours → 11,2
- David, rendu le 18/11 → 8 jours → 8 (plafond)

L'échéance est une `DATE` sans heure, donc elle tombe à minuit, fin du jour J. Le nombre de « jours entamés » est donc la différence en jours entre la date de soumission et l'échéance. C'est la même logique que le calcul existant de `late`, donc il n'y a pas besoin de toucher au schéma.

**Décision** : analyse acceptée. Trois choix sont restés ouverts (voir échange 3).

**Pourquoi** : le vrai risque de C1 n'est pas la formule de la pénalité, c'est la **règle dupliquée** dans plusieurs fichiers qui ne calculent pas tous de la même façon.

**Vérification** : lecture des fichiers et comparaison des données de `data.sql` avec les exemples de la consigne.

---

## Échange 2 — Relier le dépôt local à mon fork

**Demande** : mon dépôt local pointait encore sur le dépôt du professeur.

**Proposition** : renommer `origin` en `upstream`, puis ajouter mon fork (`Andraina600/hei-kilasy`) comme nouvel `origin`, et faire suivre `origin/main` à `main`.

**Décision** : acceptée.

**Pourquoi** : je pousse sur mon fork, et je peux toujours récupérer les mises à jour du professeur avec `git pull upstream main`.

**Vérification** : `git remote -v` affiche `origin` → mon fork et `upstream` → le dépôt du professeur. `git status -sb` affiche `main...origin/main`.

---

## Échange 3 — Choix de conception

**Demande** : trancher les points ouverts avant de coder.

**Proposition de l'IA** :
1. Pour la formule, deux options. Soit (a) la recopier dans chaque fichier, soit (b) l'écrire une seule fois dans une méthode `static` d'un fichier existant. L'IA penchait pour (a), parce que la règle du module interdit de réorganiser le code.
2. L'email : afficher seulement la note pénalisée, ou aussi la pénalité.
3. L'arrondi : `BigDecimal` HALF_UP à 1 décimale partout (CSV compris), appliqué **une seule fois, après la pénalité**.
4. Ajouter un `max(0, …)` pour garantir une note jamais négative. La base n'a pas de contrainte `CHECK`.
5. Ne rien stocker : la pénalité est calculée à la lecture, à partir de `raw_points`, `submitted_at` et `deadline`.

**Décision** :
- **(a) formule dupliquée** dans les 3 fichiers. **(b) refusée** : elle est plus propre, mais c'est un début de refactorisation, ce que la règle du module interdit. Ce sera un argument pour la clean architecture plus tard.
- L'email **indique la pénalité** de retard, sinon l'étudiant ne comprend pas sa note.
- **Même arrondi partout** (HALF_UP, 1 décimale).
- Points 4 et 5 acceptés.
- Les colonnes du CSV **restent inchangées**, parce que la scolarité l'ouvre dans Excel et un changement de format pourrait casser son usage.
- Commit : la consigne impose le message `C1`, qui n'est pas au format Conventional Commits. La consigne du cours passe avant ma préférence.

**Pourquoi** : le module cherche à faire sentir le coût d'une règle métier éparpillée. La même formule est maintenant recopiée 4 fois.

---

## Échange 4 — Implémentation

**Proposition** : la même formule dans chaque fichier, avec un seul arrondi à la fin :

```java
long daysLate = Math.max(0, ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate()));
long penaltyPercent = Math.min(daysLate * 10, 50);
BigDecimal grade = raw.multiply(BigDecimal.valueOf(20 * (100 - penaltyPercent)))
        .divide(max.multiply(BigDecimal.valueOf(100)), 1, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO);
```

Tout le calcul (`raw × 20 × (100 − pénalité) / (max × 100)`) est exact en `BigDecimal`, et l'arrondi n'a lieu qu'une fois, à la fin.

**Point soulevé par l'IA en cours de route** : dans `grade()`, la note de la correction était calculée avec les points **reçus dans la requête**, alors que le relevé et le CSV relisent les points **stockés en base**. Or la colonne `raw_points NUMERIC(5,2)` arrondit à 2 décimales. Exemple : pour 13,249 points envoyés, la base stocke 13,25. La correction aurait affiché 13,2 et le relevé 13,3.

**Décision** : accepté. Les points sont arrondis à 2 décimales (HALF_UP, comme PostgreSQL) **avant** l'`UPDATE`, et la note est calculée à partir de cette valeur arrondie.

J'ai aussi gardé l'ajout d'un champ `penaltyPercent` dans les réponses JSON (correction, consultation, relevé).

---

## Échange 5 — Vérification (manuelle : curl + Mailpit)

Application lancée avec `docker compose up -d` et `mvn spring-boot:run`.

**Exemples de la consigne** (exercice « Tri à bulles », 16/20) :

| Soumission | Rendu le | Retard | `/submissions/{id}` | Relevé | CSV |
|---|---|---|---|---|---|
| Alice | 09/11 18h00 | 0 | 16.0 | 16.0 | 16,0 |
| Felix | 10/11 23h30 (jour de l'échéance) | 0 | — | — | 15,0 (pas pénalisé) |
| Bob | 11/11 02h00 | 1 j | 14.4 | 14.4 | 14,4 |
| Clara | 13/11 10h00 | 3 j | 11.2 | 11.2 | 11,2 |
| David | 18/11 15h00 | 8 j → plafond | 8.0 | 8.0 | 8,0 |

Exercice noté sur 10 (Clara, « Pile et file », 7/10, à l'heure) : 14.0 / 14.0 / 14,0.

**Cas limite d'arrondi** : `PUT /submissions/7/grade {"points": 7.75}`. C'est Bob, « Pile et file », sur 10, 1 jour de retard : 15,5 × 0,9 = 13,95.
- Réponse de la correction : `"grade":14.0,"penaltyPercent":10`
- `GET /submissions/7` : 14.0
- Relevé de Bob : 14.0 (moyenne 14.2)
- CSV : `14,0`
- Email dans Mailpit :
  ```
  Ta note pour « Pile et file » est disponible : 14.0/20.
  Rendu avec 1 jour(s) de retard : pénalité de -10 % appliquée.
  ```

**Points stockés** : `PUT /submissions/5/grade {"points": 13.249}` → la correction affiche 13.3, `GET` affiche 13.3 (rawPoints 13.25) et le CSV affiche 13,3. Les trois sont identiques.

**Note jamais négative** : `{"points": -3}` est refusé (`Les points doivent être entre 0 et 20.0`). Le plafond à −50 % et le `max(0, …)` font qu'une note calculée ne peut pas être négative.

---

## Bilan

**Fichiers modifiés** :
- `src/main/java/hei/school/SubmissionController.java` : pénalité dans la correction (réponse + email avec la ligne de pénalité) et dans la consultation. Les points sont arrondis comme en base avant l'enregistrement.
- `src/main/java/hei/school/GradeReport.java` : pénalité dans le relevé. La moyenne en tient compte automatiquement.
- `src/main/java/hei/school/CsvExport.java` : pénalité ajoutée, et `Math.floor` remplacé par HALF_UP en `BigDecimal`.
- `JOURNAL-IA.md` : ce journal.

**Ce que l'IA a proposé et que je n'ai pas gardé** : la méthode `static` partagée (option b), et l'ajout d'une colonne de pénalité dans le CSV.

**Ce que je retiens** :
- La même règle métier (calcul de la note + retard) est maintenant **recopiée 4 fois**.
- Il y avait un bug d'arrondi **préexistant** (le `floor` du CSV), qui n'est apparu qu'en comparant les 4 endroits.
- Le prochain changement de la règle (par exemple un autre taux de pénalité) devra être fait à 4 endroits sans en oublier un.

**Mauvaises pratiques repérées, hors périmètre, non corrigées** :
- `SELECT max(id)` après un `INSERT` : risque de collision quand deux requêtes arrivent en même temps.
- Le CSV n'échappe pas les `;`.
- `LocalDateTime.now()` dépend du fuseau horaire du serveur.
