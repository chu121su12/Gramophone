#!/usr/bin/env bash
set -euo pipefail

mode="generate"
if [[ "${1:-}" == "--check-remote" ]]; then
    mode="check-remote"
    shift
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
config_file="${1:-"$script_dir/ipadic.properties"}"
config_dir="$(CDPATH= cd -- "$(dirname -- "$config_file")" && pwd)"
config_file="$config_dir/$(basename -- "$config_file")"
output_file="${2:-"$config_dir/../../../../build/generated/res/japaneseIpadic/raw/japanese_ipadic.tsv"}"

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

property() {
    local key="$1"
    awk -v key="$key" '
        index($0, key "=") == 1 {
            print substr($0, length(key) + 2)
            found = 1
        }
        END {
            if (!found) exit 1
        }
    ' "$config_file" || fail "Missing dictionary property: $key"
}

optional_property() {
    local key="$1"
    awk -v key="$key" '
        index($0, key "=") == 1 {
            print substr($0, length(key) + 2)
            found = 1
        }
        END {
            if (!found) exit 0
        }
    ' "$config_file"
}

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 -r "$1" | awk '{ print $1 }'
    else
        fail "Need sha256sum, shasum, or openssl"
    fi
}

lowercase() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

header_value() {
    local key="$1"
    awk -v key="$key" '
        {
            line = $0
            sub(/\r$/, "", line)
            lower = tolower(line)
            target = tolower(key) ":"
            if (index(lower, target) == 1) {
                sub(/^[^:]*:[[:space:]]*/, "", line)
                value = line
            }
        }
        END {
            print value
        }
    '
}

assert_header_matches() {
    local key="$1"
    local actual="$2"
    local expected="$3"
    if [[ -n "$expected" && -n "$actual" && "$actual" != "$expected" ]]; then
        fail "Remote Japanese IPADIC dictionary changed: $key expected $expected, got $actual"
    fi
}

check_remote_headers() {
    local headers
    headers="$(curl -fsSIL --connect-timeout 15 --max-time 60 "$dict_url")"
    assert_header_matches \
        "kuromoji.dict.content-length" \
        "$(printf '%s\n' "$headers" | header_value "content-length")" \
        "$expected_length"
    assert_header_matches \
        "kuromoji.dict.etag" \
        "$(printf '%s\n' "$headers" | header_value "etag")" \
        "$expected_etag"
    assert_header_matches \
        "kuromoji.dict.last-modified" \
        "$(printf '%s\n' "$headers" | header_value "last-modified")" \
        "$expected_last_modified"
}

verify_archive() {
    local archive="$1"
    if [[ ! -f "$archive" ]]; then
        printf '%s\n' "archive missing"
        return 1
    fi
    if [[ -n "$expected_length" ]]; then
        local actual_length
        actual_length="$(wc -c < "$archive" | awk '{ print $1 }')"
        if [[ "$actual_length" != "$expected_length" ]]; then
            printf 'content length expected %s, got %s\n' "$expected_length" "$actual_length"
            return 1
        fi
    fi
    if [[ -n "$expected_sha256" ]]; then
        local actual_sha256
        actual_sha256="$(sha256_file "$archive")"
        if [[ "$(lowercase "$actual_sha256")" != "$(lowercase "$expected_sha256")" ]]; then
            printf 'sha256 expected %s, got %s\n' "$expected_sha256" "$actual_sha256"
            return 1
        fi
    fi
}

download_archive() {
    local temp="$archive_file.tmp"
    curl -fL --retry 3 --connect-timeout 15 --max-time 300 -o "$temp" "$dict_url"
    mv "$temp" "$archive_file"
}

cache_archive() {
    mkdir -p "$cache_dir"
    local mismatch
    if ! mismatch="$(verify_archive "$archive_file")"; then
        printf 'Caching Japanese IPADIC dictionary: %s\n' "$mismatch"
        check_remote_headers
        download_archive
    fi
    if ! mismatch="$(verify_archive "$archive_file")"; then
        fail "Cached Japanese IPADIC dictionary is invalid: $mismatch"
    fi
    {
        printf 'kuromoji.dict.file=%s\n' "$dict_file"
        printf 'kuromoji.dict.url=%s\n' "$dict_url"
        printf 'kuromoji.dict.sha256=%s\n' "$(sha256_file "$archive_file")"
        printf 'kuromoji.dict.content-length=%s\n' "$(wc -c < "$archive_file" | awk '{ print $1 }')"
        [[ -n "$expected_etag" ]] && printf 'kuromoji.dict.etag=%s\n' "$expected_etag"
        [[ -n "$expected_last_modified" ]] && printf 'kuromoji.dict.last-modified=%s\n' "$expected_last_modified"
    } > "$archive_file.properties"
}

extract_archive() {
    local archive_sha
    archive_sha="$(sha256_file "$archive_file")"
    local marker="$dict_dir/.archive.sha256"
    if [[ -d "$dict_dir" && -f "$marker" && "$(cat "$marker")" == "$archive_sha" ]]; then
        return
    fi

    local temp_dir="$cache_dir/.extract.$$"
    rm -rf "$temp_dir"
    mkdir -p "$temp_dir"
    tar -xzf "$archive_file" -C "$temp_dir"
    local extracted="$temp_dir/$(basename -- "$dict_dir")"
    [[ -d "$extracted" ]] || fail "Extracted Japanese IPADIC directory missing: $extracted"
    rm -rf "$dict_dir"
    mv "$extracted" "$dict_dir"
    rm -rf "$temp_dir"
    printf '%s\n' "$archive_sha" > "$marker"
}

generate_dictionary() {
    command -v perl >/dev/null 2>&1 || fail "Need perl to build Japanese IPADIC TSV"
    mkdir -p "$(dirname -- "$output_file")"
    local temp_output="$output_file.tmp"
    find "$dict_dir" -type f -name '*.csv' -print0 | IPADIC_ENCODING="$dict_encoding" perl -MEncode=decode -CS -e '
        use strict;
        use warnings;
        use utf8;
        binmode STDOUT, ":encoding(UTF-8)";

        my $encoding = $ENV{"IPADIC_ENCODING"} || "euc-jp";
        my $entry_cost = 100;
        my $particle_cost = 2_000;
        my $proper_noun_cost = 5_000;
        my $suffix_cost_discount = 3_000;
        my $counter_compound_cost = 3_000;
        my $derived_compound_cost = 300;
        my %best;
        my %exact_general_surface;
        my %readings_by_surface;
        my %counter_prefixes;
        my %counter_suffixes;
        my %suffix_by_last_char;
        my %suffix_seen;
        my @compound_sources;
        local $/ = "\0";
        while (my $path = <STDIN>) {
            chomp $path;
            open my $fh, "<:raw", $path or die "Failed to read $path: $!\n";
            {
                local $/ = "\n";
                while (my $line = <$fh>) {
                    $line = decode($encoding, $line);
                    chomp $line;
                    my @fields = split /,/, $line, 13;
                    next if @fields < 12;
                    my ($surface, $cost) = ($fields[0], $fields[3]);
                    my $pronunciation = @fields > 12 ? $fields[12] : "";
                    my $reading = $pronunciation ne "" && $pronunciation ne "*" ? $pronunciation : $fields[11];
                    next if $cost !~ /^-?\d+$/;
                    next if $reading eq "" || $reading eq "*";
                    my $is_proper_noun = $fields[4] eq "名詞" && $fields[5] eq "固有名詞";
                    my $is_counter_prefix = $fields[4] eq "名詞" && $fields[5] eq "数" &&
                        $surface =~ /\p{Script=Han}/;
                    my $is_counter_suffix = $fields[4] eq "名詞" && $fields[5] eq "接尾" &&
                        $fields[6] eq "助数詞";
                    my $is_general_suffix = $fields[4] eq "名詞" && $fields[5] eq "接尾" &&
                        $fields[6] eq "一般" && $surface =~ /\p{Script=Han}/;
                    my $is_particle = $fields[4] eq "助詞" && length($surface) == 1;
                    my $is_kana_word = $surface =~ /^[\p{Script=Hiragana}\p{Script=Katakana}ー]+$/ &&
                        length($surface) > 1;
                    if ($fields[4] eq "名詞" && $fields[5] eq "接尾") {
                        my $suffix_key = join "\t", $surface, $reading;
                        if (!$suffix_seen{$suffix_key}++) {
                            push @{$suffix_by_last_char{substr($surface, -1)}}, [$surface, $reading];
                        }
                    }
                    next if $surface !~ /\p{Script=Han}/ && !$is_particle && !$is_kana_word;
                    my $kind = "";
                    if ($is_particle) {
                        $kind = "particle";
                    } elsif ($is_general_suffix) {
                        $kind = "suffix";
                    } elsif ($reading =~ /[ッっ]\z/) {
                        if ($surface =~ /[ッっ]\z/) {
                            $reading =~ s/[ッっ]+\z//;
                            $kind = "geminateNext";
                        } else {
                            $reading = "_";
                        }
                    } elsif (length($surface) == 1 && $fields[4] ne "名詞") {
                        next if $fields[4] ne "動詞" && $fields[4] ne "形容詞";
                        $kind = "kanaSuffix";
                    }
                    $cost += $entry_cost;
                    $cost += $particle_cost if $kind eq "particle";
                    $cost += $proper_noun_cost if $is_proper_noun;
                    $cost -= $suffix_cost_discount if $kind eq "suffix";
                    $readings_by_surface{$surface}{$reading} = 1
                        if $kind eq "" && $surface =~ /\p{Script=Han}/;
                    if (!$is_proper_noun && $kind eq "" && $surface =~ /\p{Script=Han}/) {
                        $exact_general_surface{$surface} = 1;
                        push @compound_sources, [$surface, $reading, $cost] if length($surface) > 2;
                    }
                    if ($is_counter_prefix && $kind eq "") {
                        $counter_prefixes{join "\t", $surface, $reading} = [$surface, $reading];
                    }
                    if ($is_counter_suffix && $kind eq "") {
                        $counter_suffixes{join "\t", $surface, $reading} = [$surface, $reading];
                    }
                    my $key = join "\t", $surface, $kind;
                    if (!exists $best{$key} || $cost < $best{$key}[2]) {
                        $best{$key} = [$surface, $reading, $cost, $kind];
                    }
                }
            }
            close $fh;
        }

        for my $suffix (values %counter_suffixes) {
            my ($suffix_surface, $suffix_reading) = @$suffix;
            my ($matching_examples, $mismatching_examples) = (0, 0);
            for my $prefix (values %counter_prefixes) {
                my ($prefix_surface, $prefix_reading) = @$prefix;
                my $compound_surface = $prefix_surface . $suffix_surface;
                my $readings = $readings_by_surface{$compound_surface} || next;
                if ($readings->{$prefix_reading . $suffix_reading}) {
                    $matching_examples++;
                } else {
                    $mismatching_examples++;
                }
            }
            next if $matching_examples == 0 || $mismatching_examples > 0;

            for my $prefix (values %counter_prefixes) {
                my ($prefix_surface, $prefix_reading) = @$prefix;
                my $compound_surface = $prefix_surface . $suffix_surface;
                next if $exact_general_surface{$compound_surface};
                my $key = join "\t", $compound_surface, "";
                if (!exists $best{$key} || $counter_compound_cost < $best{$key}[2]) {
                    $best{$key} = [
                        $compound_surface,
                        $prefix_reading . $suffix_reading,
                        $counter_compound_cost,
                        ""
                    ];
                }
            }
        }

        # Recover common prefix compounds missing as standalone IPADIC entries.
        for my $source (@compound_sources) {
            my ($surface, $reading, $cost) = @$source;
            for my $suffix (@{$suffix_by_last_char{substr($surface, -1)} || []}) {
                my ($suffix_surface, $suffix_reading) = @$suffix;
                next if length($suffix_surface) >= length($surface);
                next if length($suffix_reading) >= length($reading);
                next if substr($surface, -length($suffix_surface)) ne $suffix_surface;
                next if substr($reading, -length($suffix_reading)) ne $suffix_reading;

                my $prefix_surface = substr($surface, 0, length($surface) - length($suffix_surface));
                next if length($prefix_surface) < 2;
                next if $prefix_surface !~ /\p{Script=Han}/;
                next if $exact_general_surface{$prefix_surface};

                my $prefix_reading = substr($reading, 0, length($reading) - length($suffix_reading));
                next if $prefix_reading eq "";
                my $key = join "\t", $prefix_surface, "";
                my $derived_cost = $cost + $derived_compound_cost;
                if (!exists $best{$key} || $derived_cost < $best{$key}[2]) {
                    $best{$key} = [$prefix_surface, $prefix_reading, $derived_cost, ""];
                }
            }
        }

        for my $key (sort keys %best) {
            my ($surface, $reading, $cost, $kind) = @{$best{$key}};
            print $surface, "\t", $reading, "\t", $cost;
            print "\t", $kind if $kind ne "";
            print "\n";
        }
    ' > "$temp_output"
    mv "$temp_output" "$output_file"
}

dict_file="$(property "kuromoji.dict.file")"
dict_url_template="$(property "kuromoji.dict.url")"
dict_url="${dict_url_template//\$\{kuromoji.dict.file\}/$dict_file}"
dict_dir="$config_dir/$(property "kuromoji.dict.dir")"
dict_encoding="$(property "kuromoji.dict.encoding")"
expected_length="$(optional_property "kuromoji.dict.content-length")"
expected_etag="$(optional_property "kuromoji.dict.etag")"
expected_last_modified="$(optional_property "kuromoji.dict.last-modified")"
expected_sha256="$(optional_property "kuromoji.dict.sha256")"
cache_dir="$(dirname -- "$dict_dir")"
archive_file="$cache_dir/$dict_file"

if [[ "$mode" == "check-remote" ]]; then
    check_remote_headers
    exit 0
fi

cache_archive
extract_archive
generate_dictionary
