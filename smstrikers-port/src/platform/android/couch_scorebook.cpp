#if defined(__ANDROID__) && defined(PORT_USE_AURORA)

#include "port/couch_scorebook.h"
#include "port/config.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <random>

namespace {
unsigned matchPlayers[2];
int matchHumans[2];
PortCouchNight night;
unsigned cupOpponents[kCouchRosterSize];
constexpr unsigned rosterMask = (1u << kCouchRosterSize) - 1;

int player_count(unsigned players)
{
    int count = 0;
    for (; players; players &= players - 1)
        ++count;
    return count;
}

void tally(PortCouchStanding* standings, const PortCouchResult& result)
{
    for (int i = 0; i < kCouchRosterSize; ++i)
    {
        const unsigned player = 1u << i;
        const bool home = (result.homePlayers & player) != 0;
        if (!home && !(result.awayPlayers & player))
            continue;
        const int scored = home ? result.homeScore : result.awayScore;
        const int conceded = home ? result.awayScore : result.homeScore;
        auto& standing = standings[i];
        standing.wins += scored > conceded;
        standing.losses += scored < conceded;
        standing.goalsFor += scored;
        standing.goalsAgainst += conceded;
    }
}

void next_cup_match()
{
    night.lineup[0] = night.lineup[1] = 0;
    for (int first = 0; first < kCouchRosterSize; ++first)
    {
        const unsigned player = 1u << first;
        if (!(night.players & player))
            continue;
        const unsigned remaining = night.players & ~(cupOpponents[first] | player);
        if (remaining)
        {
            night.lineup[0] = player;
            night.lineup[1] = remaining & -remaining;
            return;
        }
    }
}

void update_night(const PortCouchResult& result)
{
    if (!night.active || result.homeScore == result.awayScore)
        return;
    const unsigned home = result.homePlayers, away = result.awayPlayers;
    if (!home || !away || ((home | away) & ~night.players) ||
        player_count(home) != matchHumans[0] || player_count(away) != matchHumans[1])
        return;

    tally(night.standings, result);
    night.recentCount = std::min(night.recentCount + 1, 5);
    for (int i = night.recentCount - 1; i > 0; --i)
        night.recent[i] = night.recent[i - 1];
    night.recent[0] = result;

    if (night.mode == PortCouchMode::Series && !night.lineup[0])
    {
        night.lineup[0] = home;
        night.lineup[1] = away;
    }
    const bool straight = home == night.lineup[0] && away == night.lineup[1];
    const bool reversed = away == night.lineup[0] && home == night.lineup[1];
    if (!straight && !reversed)
        return;
    const int winner = (result.homeScore > result.awayScore) == straight ? 0 : 1;
    switch (night.mode)
    {
    case PortCouchMode::Rotation:
    {
        unsigned& losingPair = night.lineup[1 - winner];
        int out = -1;
        for (int i = 0; i < kCouchRosterSize; ++i)
            if ((losingPair & (1u << i)) && (out < 0 || night.sits[i] < night.sits[out]))
                out = i;
        const unsigned player = 1u << out;
        losingPair = (losingPair & ~player) | night.waiting;
        night.waiting = player;
        ++night.sits[out];
        break;
    }
    case PortCouchMode::Series:
        if (night.seriesWins[0] < night.winsNeeded && night.seriesWins[1] < night.winsNeeded)
            ++night.seriesWins[winner];
        break;
    case PortCouchMode::Cup:
        for (int i = 0; i < kCouchRosterSize; ++i)
        {
            const unsigned player = 1u << i;
            if (player & home)
                cupOpponents[i] |= away;
            if (player & away)
                cupOpponents[i] |= home;
        }
        tally(night.cup, result);
        ++night.cupPlayed;
        next_cup_match();
        break;
    case PortCouchMode::OpenPlay:
        break;
    }
}

bool path_for(char (&path)[1100], const char* name)
{
    char directory[1024];
    if (PortConfigDir(directory, sizeof directory) != 0)
        return false;
    return std::snprintf(path, sizeof path, "%s/%s", directory, name) < (int)sizeof path;
}

FILE* open_scorebook(const char* mode)
{
    char path[1100];
    return path_for(path, "couch-scorebook.txt") ? std::fopen(path, mode) : nullptr;
}

bool parse_result(const char* line, PortCouchResult& result)
{
    result = {};
    int fields = std::sscanf(line, "%d %d %d %d %u %u", &result.homeTeam, &result.awayTeam,
                            &result.homeScore, &result.awayScore, &result.homePlayers,
                            &result.awayPlayers);
    if (fields != 6)
        result.homePlayers = result.awayPlayers = 0;
    return fields == 4 || fields == 6;
}
}

void PortCouchBeginMatch(const short (&sides)[4])
{
    PortCouchRoster roster;
    PortCouchLoadRoster(roster);
    matchPlayers[0] = matchPlayers[1] = 0;
    matchHumans[0] = matchHumans[1] = 0;
    for (int port = 0; port < 4; ++port)
    {
        const short side = sides[port];
        if (side != 0 && side != 1)
            continue;
        ++matchHumans[side];
        if (roster.ports[port] != 0)
            matchPlayers[side] |= 1u << (roster.ports[port] - 1);
    }
}

void PortCouchRecordResult(const PortCouchResult& result)
{
    PortCouchResult named = result;
    named.homePlayers = matchPlayers[0];
    named.awayPlayers = matchPlayers[1];
    update_night(named);
    FILE* file = open_scorebook("a");
    if (file == nullptr)
    {
        std::fprintf(stderr, "[scorebook] Could not open match history\n");
        return;
    }
    const int written = std::fprintf(file, "%d %d %d %d %u %u\n", result.homeTeam,
                                      result.awayTeam, result.homeScore, result.awayScore,
                                      matchPlayers[0], matchPlayers[1]);
    if (std::fclose(file) != 0 || written < 0)
        std::fprintf(stderr, "[scorebook] Could not save match result\n");
}

int PortCouchRecentResults(PortCouchResult* results, int capacity)
{
    if (capacity <= 0)
        return 0;
    FILE* file = open_scorebook("r");
    if (file == nullptr)
        return 0;

    char line[128];
    int count = 0;
    while (std::fgets(line, sizeof line, file) != nullptr)
    {
        PortCouchResult result;
        if (!parse_result(line, result))
            continue;
        if (count == capacity)
        {
            for (int i = 1; i < count; ++i)
                results[i - 1] = results[i];
            --count;
        }
        results[count++] = result;
    }
    std::fclose(file);
    return count;
}

void PortCouchLoadRoster(PortCouchRoster& roster)
{
    roster = {};
    char path[1100];
    if (!path_for(path, "couch-roster.txt"))
        return;
    FILE* file = std::fopen(path, "r");
    if (file == nullptr)
        return;
    char line[64];
    if (std::fgets(line, sizeof line, file) != nullptr)
        std::sscanf(line, "%d %d %d %d", &roster.ports[0], &roster.ports[1],
                    &roster.ports[2], &roster.ports[3]);
    for (auto& name : roster.names)
    {
        if (std::fgets(line, sizeof line, file) == nullptr)
            break;
        line[std::strcspn(line, "\r\n")] = '\0';
        std::snprintf(name, sizeof name, "%.*s", kCouchNameLength, line);
    }
    unsigned assigned = 0;
    for (int& port : roster.ports)
    {
        if (port < 1 || port > kCouchRosterSize || !roster.names[port - 1][0]
            || (assigned & (1u << (port - 1))))
            port = 0;
        else
            assigned |= 1u << (port - 1);
    }
    std::fclose(file);
}

bool PortCouchSaveRoster(const PortCouchRoster& roster)
{
    char path[1100], temp[1100];
    if (!path_for(path, "couch-roster.txt") || !path_for(temp, "couch-roster.tmp"))
        return false;
    FILE* file = std::fopen(temp, "w");
    if (file == nullptr)
        return false;
    bool ok = std::fprintf(file, "%d %d %d %d\n", roster.ports[0], roster.ports[1],
                           roster.ports[2], roster.ports[3]) >= 0;
    for (const auto& name : roster.names)
        ok = std::fprintf(file, "%s\n", name) >= 0 && ok;
    ok = std::fclose(file) == 0 && ok;
    if (ok && std::rename(temp, path) == 0)
        return true;
    std::remove(temp);
    return false;
}

void PortCouchRivalry(int first, int second, int& wins, int& losses)
{
    wins = losses = 0;
    if (first < 1 || first > kCouchRosterSize || second < 1 || second > kCouchRosterSize
        || first == second)
        return;
    FILE* file = open_scorebook("r");
    if (file == nullptr)
        return;
    const unsigned a = 1u << (first - 1), b = 1u << (second - 1);
    char line[128];
    while (std::fgets(line, sizeof line, file) != nullptr)
    {
        PortCouchResult result;
        if (!parse_result(line, result) || result.homeScore == result.awayScore)
            continue;
        const bool aHome = (result.homePlayers & a) && (result.awayPlayers & b);
        const bool aAway = (result.awayPlayers & a) && (result.homePlayers & b);
        if (aHome == aAway)
            continue;
        if (aHome == (result.homeScore > result.awayScore))
            ++wins;
        else
            ++losses;
    }
    std::fclose(file);
}

const PortCouchNight& PortCouchGetNight()
{
    return night;
}

void PortCouchStartNight(unsigned players)
{
    night = {};
    night.players = players & rosterMask;
    night.active = player_count(night.players) >= 2;
}

void PortCouchEndNight()
{
    night.active = false;
}

bool PortCouchDrawTeams(bool balanced)
{
    if (!night.active || (night.mode != PortCouchMode::OpenPlay &&
                          night.mode != PortCouchMode::Rotation))
        return false;
    const int count = player_count(night.players);
    if (count < 4 || count > 5)
        return false;
    unsigned players[5];
    int index = 0;
    for (int i = 0; i < kCouchRosterSize; ++i)
        if (night.players & (1u << i))
            players[index++] = 1u << i;

    static std::mt19937 random(std::random_device{}());
    std::shuffle(players, players + count, random);
    if (balanced)
    {
        PortCouchStanding history[kCouchRosterSize]{};
        if (FILE* file = open_scorebook("r"))
        {
            char line[128];
            PortCouchResult result;
            while (std::fgets(line, sizeof line, file))
                if (parse_result(line, result))
                    tally(history, result);
            std::fclose(file);
        }
        auto rating = [&](unsigned player) {
            const auto& record = history[player_count(player - 1)];
            return (record.wins + 1.0) / (record.wins + record.losses + 2.0);
        };
        std::sort(players, players + 4, [&](unsigned a, unsigned b) {
            return rating(a) < rating(b);
        });
        std::swap(players[1], players[3]);
    }
    night.lineup[0] = players[0] | players[1];
    night.lineup[1] = players[2] | players[3];
    night.waiting = count == 5 ? players[4] : 0;
    if (night.waiting && night.mode == PortCouchMode::Rotation)
        ++night.sits[player_count(night.waiting - 1)];
    return true;
}

bool PortCouchStartMode(PortCouchMode mode, int winsNeeded)
{
    if (!night.active)
        return false;
    const int count = player_count(night.players);
    if ((mode == PortCouchMode::Rotation && count != 5) ||
        (mode == PortCouchMode::Cup && count != 4 && count != 5) ||
        (mode == PortCouchMode::Series && winsNeeded != 2 && winsNeeded != 3))
        return false;

    night.mode = mode;
    night.lineup[0] = night.lineup[1] = night.waiting = 0;
    switch (mode)
    {
    case PortCouchMode::Rotation:
        std::fill_n(night.sits, kCouchRosterSize, 0);
        return PortCouchDrawTeams(false);
    case PortCouchMode::Series:
        night.winsNeeded = winsNeeded;
        night.seriesWins[0] = night.seriesWins[1] = 0;
        break;
    case PortCouchMode::Cup:
        std::fill_n(night.cup, kCouchRosterSize, PortCouchStanding{});
        std::fill_n(cupOpponents, kCouchRosterSize, 0u);
        night.cupPlayed = 0;
        next_cup_match();
        break;
    case PortCouchMode::OpenPlay:
        break;
    }
    return true;
}

#endif
