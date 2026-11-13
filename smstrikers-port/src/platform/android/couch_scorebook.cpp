#if defined(__ANDROID__) && defined(PORT_USE_AURORA)

#include "port/couch_scorebook.h"
#include "port/config.h"

#include <cstdio>
#include <cstring>

namespace {
unsigned matchPlayers[2];
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
    for (int port = 0; port < 4; ++port)
        if (roster.ports[port] != 0 && (sides[port] == 0 || sides[port] == 1))
            matchPlayers[sides[port]] |= 1u << (roster.ports[port] - 1);
}

void PortCouchRecordResult(const PortCouchResult& result)
{
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

#endif
