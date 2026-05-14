"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { getCurrentUser } from "@/lib/auth";

export default function Home() {
  const router = useRouter();

  useEffect(() => {
    getCurrentUser()
      .then(() => router.replace("/chat"))
      .catch(() => router.replace("/auth/login"));
  }, [router]);

  return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
}
