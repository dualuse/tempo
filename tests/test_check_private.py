#!/usr/bin/env python3
"""Tests for tools/check-private.sh, the private-term check, run in a scratch repository.

    python3 -m unittest tests.test_check_private

Python 3.8+, standard library only; needs git and sh.
"""

import os
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "tools", "check-private.sh")
HOOK_ARGS = ["origin", "https://example.invalid/x.git"]  # what git passes a pre-push hook


class CheckPrivateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        self.list = os.path.join(self.tmp.name, "denylist")
        with open(self.list, "w") as f:
            f.write("# made-up terms\n\nfrobnicat\nQuux-[0-9]+\n")
        os.mkdir(self.repo)
        self.git("init", "-q")
        self.commit("a.txt", "nothing to see\n", "first")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
                   GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
        subprocess.run(["git", "-c", "commit.gpgsign=false", *args], cwd=self.repo, env=env,
                       check=True, stdout=subprocess.DEVNULL)

    def commit(self, path, text, message):
        with open(os.path.join(self.repo, path), "w") as f:
            f.write(text)
        self.git("add", path)
        self.git("commit", "-q", "-m", message)

    def run_check(self, *args):
        env = dict(os.environ, TEMPO_DENYLIST=self.list)
        return subprocess.run(["sh", SCRIPT, *args], cwd=self.repo, env=env, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)

    def test_clean_tree_passes_with_and_without_hook_arguments(self):
        for args in ([], HOOK_ARGS, ["--names-only"], ["--names-only", *HOOK_ARGS]):
            r = self.run_check(*args)
            self.assertEqual(0, r.returncode, (args, r.stderr))
            self.assertIn("tree and history clean (2 patterns)", r.stderr)

    def test_hit_in_tree_and_history_fails_under_the_hook(self):
        self.commit("b.txt", "a quux-42 here\n", "second")
        r = self.run_check(*HOOK_ARGS)
        self.assertEqual(1, r.returncode, r.stderr)
        self.assertIn("b.txt:1:a quux-42 here", r.stderr)
        self.assertIn("private terms in the history", r.stderr)

    def test_names_only_hides_the_matching_text(self):
        self.commit("b.txt", "frobnicate\n", "second")
        r = self.run_check("--names-only", *HOOK_ARGS)
        self.assertEqual(1, r.returncode, r.stderr)
        self.assertIn("b.txt", r.stderr)
        self.assertNotIn("frobnicate", r.stderr)

    def test_hit_only_in_a_commit_message_fails(self):
        self.commit("b.txt", "fine\n", "mention Frobnicator")
        r = self.run_check(*HOOK_ARGS)
        self.assertEqual(1, r.returncode, r.stderr)
        self.assertNotIn("private terms in tracked files", r.stderr)

    def test_unknown_option_is_a_usage_error(self):
        r = self.run_check("--bogus")
        self.assertEqual(2, r.returncode)
        self.assertIn("usage:", r.stderr)


if __name__ == "__main__":
    unittest.main()
